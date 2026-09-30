package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SupplierOrderEmail.EmailPreview;
import com.trophy.promostandards.sync.SupplierOrderService.Line;
import com.trophy.promostandards.sync.SupplierOrderService.Preview;
import com.trophy.promostandards.sync.SupplierOrderService.ShipTo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The message that would reach PaceSetter: filled from the order, escaped, and gated on configuration. */
class SupplierOrderEmailTest {

    private static final ShipTo ADDRESS = new ShipTo("Jane Buyer", null, "1 Main St", null,
            "Wake Forest", "North Carolina", "NC", "27587", "United States", "US", null);

    private static Preview order(List<Line> lines, String note, List<String> blocking) {
        return order(lines, note, blocking, "5/11/2026");
    }

    private static Preview order(List<Line> lines, String note, List<String> blocking, String dateNeeded) {
        return new Preview("gid://shopify/Order/7291179761758", "#1046", "1046", "2026-09-22T16:48:24Z",
                true, "PAID", "UNFULFILLED", note, dateNeeded, ADDRESS, "Standard", lines, List.of(),
                blocking, List.of(), null);
    }

    private static Line line(String partId, String title, Map<String, String> personalization) {
        return new Line(partId, title, null, "PS9250", 2, 2, new BigDecimal("36.99"), "USD", personalization, null);
    }

    private static SupplierOrderProperties props(String template) {
        return new SupplierOrderProperties(false, "orders@pacesetterawards.com", "shop@trophypartner.com",
                null, "orders@trophypartner.com", null, "Purchase Order {{poNumber}}", template, "TP-4412",
                "Matt Gunn", "Dana", "UPS #4E4W93");
    }

    /** An SMTP setup with nothing wrong with it: the mail server is not what these tests are about. */
    private static SupplierOrderEmail email(SupplierOrderProperties props) {
        SupplierOrderMailer mailer = org.mockito.Mockito.mock(SupplierOrderMailer.class);
        org.mockito.Mockito.when(mailer.problem()).thenReturn(null);
        return new SupplierOrderEmail(props, new DefaultResourceLoader(), mailer);
    }

    private static Line customized(String partId, int quantity, TrophyItem item) {
        return new Line(partId, "Red/Black Spiral Teardrop Art Glass", null, "PS9349", quantity, quantity,
                new BigDecimal("51.00"), "USD", Map.of(), item);
    }

    /** {@code n} pieces, each two lines in two fonts — the shape of #1049, at any size. */
    private static TrophyItem pieces(int n) {
        StringBuilder items = new StringBuilder();
        for (int i = 1; i <= n; i++) {
            items.append(i == 1 ? "" : ",").append("""
                    {"item":%d,"values":{"engraving":{"line-1":"Name %d","line-1-font":"Open Sans",\
                    "line-2":"Team \\"A\\", 2026","line-2-font":"Bebas Neue"}}}""".formatted(i, i));
        }
        return TrophyItem.parse("{\"preview\":\"https://cdn.shopify.com/ss-1.png?v=1\",\"items\":[" + items + "]}");
    }

    /** #1049: every piece in the body with its fonts and the artwork link, and the sheet attached. */
    @Test
    void writesEachPieceWithItsFontsAndAttachesTheSheet() {
        EmailPreview mail = email(props(null)).render(order(List.of(customized("GM828", 3,
                TrophyItem.parse(TrophyItemTest.ORDER_1049))), null, List.of()));

        assertThat(mail.body()).contains("Piece 1", "textline1", "(Bebas Neue)", "Piece 3", "line2",
                "Fonts:</span> Open Sans, Bebas Neue",
                "href=\"https://cdn.shopify.com/s/files/1/0655/6231/2798/files/trophy-preview-p-8159-redblack-spiral-teardrop-art-glass-1790501535902.png?v=1790501537\"",
                "attached as <strong>PO-1046-engraving.csv</strong>");
        assertThat(mail.body()).doesNotContain("in the attached engraving sheet.");   // all 3 fit
        assertThat(mail.text()).contains("Line 2: test 2 (Open Sans)");
        assertThat(mail.attachments()).singleElement().satisfies(a -> {
            assertThat(a.filename()).isEqualTo("PO-1046-engraving.csv");
            assertThat(a.contentType()).isEqualTo("text/csv");
            assertThat(a.content().split("\r\n")).hasSize(1 + 6);   // header + 3 pieces × 2 lines
            assertThat(a.content()).contains(
                    "\"1046\",\"1\",\"GM828\",\"Red/Black Spiral Teardrop Art Glass\",\"3\",\"1\",\"Line 2\",\"text line2 \",\"Bebas Neue\"");
        });
    }

    /**
     * 500 pieces: the body lists none — never the first few, which would be worked from and the rest
     * missed — and says where they are; the sheet has all of them.
     */
    @Test
    void aLargeOrderListsNoPieceInTheBodyAndAllInTheSheet() {
        EmailPreview mail = email(props(null)).render(order(List.of(customized("GM828", 500, pieces(500))),
                null, List.of()));

        assertThat(mail.body()).doesNotContain("Piece 1<", "Name 1")
                .contains("500 pieces &mdash; engraving in the attached PO-1046-engraving.csv",
                        "Fonts:</span> Open Sans, Bebas Neue", "This email does not list them.");
        assertThat(mail.body().length()).isLessThan(15_000);
        String csv = mail.attachments().get(0).content();
        assertThat(csv.split("\r\n")).hasSize(1 + 1000);
        assertThat(csv).startsWith("﻿\"PO\"").contains("\"Name 500\"", "\"Team \"\"A\"\", 2026\"");
    }

    /** The limit is the order's pieces, not a line's: 3 + 3 is over it, so neither line lists them. */
    @Test
    void theLimitCountsEveryPieceOfTheOrder() {
        EmailPreview atLimit = email(props(null)).render(order(List.of(
                customized("GM828", 3, pieces(3)), customized("GM829", 2, pieces(2))), null, List.of()));
        EmailPreview over = email(props(null)).render(order(List.of(
                customized("GM828", 3, pieces(3)), customized("GM829", 3, pieces(3))), null, List.of()));

        assertThat(atLimit.body()).contains("Piece 3", "also attached as");
        assertThat(over.body()).doesNotContain("Piece 1<").contains("3 pieces &mdash; engraving in the attached");
    }

    /** One logo and one preview per order: written once above the table, never on each line or CSV row. */
    @Test
    void writesTheSharedArtworkOnceAndSplitsItOnlyWhenLinesDiffer() {
        String preview = "https://cdn.shopify.com/ss-1.png?v=1";
        EmailPreview shared = email(props(null)).render(order(List.of(
                customized("GM828", 3, pieces(3)), customized("GM829", 2, pieces(2))), null, List.of()));

        assertThat(shared.body()).contains("Artwork for every piece in this order");
        assertThat(shared.body().split(java.util.regex.Pattern.quote("href=\"" + preview + "\""))).hasSize(2);
        assertThat(shared.attachments().get(0).content()).doesNotContain(preview);

        TrophyItem other = TrophyItem.parse("{\"preview\":\"https://cdn.shopify.com/ss-2.png\",\"items\":[{\"item\":1}]}");
        EmailPreview differing = email(props(null)).render(order(List.of(
                customized("GM828", 3, pieces(3)), customized("GM829", 1, other)), null, List.of()));

        assertThat(differing.body()).doesNotContain("Artwork for every piece")
                .contains("href=\"" + preview + "\"", "href=\"https://cdn.shopify.com/ss-2.png\"");
    }

    /**
     * #1051: GM828 × 10 customized and CB35 × 1 with nothing to engrave. The sheet is what PaceSetter
     * works from, so it lists both — a product missing from it reads as a product not ordered.
     */
    @Test
    void theSheetListsEveryLineOfTheOrderNotOnlyTheCustomizedOnes() {
        EmailPreview mail = email(props(null)).render(order(List.of(customized("GM828", 10, pieces(10)),
                line("CB35", "Base", Map.of()), line("GI307", "Plaque", Map.of("Line 1", "Coach Ann"))),
                null, List.of()));

        String csv = mail.attachments().get(0).content();
        assertThat(csv.split("\r\n")).hasSize(1 + 20 + 1 + 1);
        assertThat(csv).contains("\"Quantity\"",
                "\"1046\",\"1\",\"GM828\",\"Red/Black Spiral Teardrop Art Glass\",\"10\",\"10\",\"Line 1\",\"Name 10\"",
                "\"1046\",\"2\",\"CB35\",\"Base\",\"2\",\"\",\"\",\"\",\"\"",
                "\"1046\",\"3\",\"GI307\",\"Plaque\",\"2\",\"\",\"Line 1\",\"Coach Ann\",\"\"");
    }

    @Test
    void attachesNothingWhenNoLineCarriesTheCustomizersRecord() {
        EmailPreview mail = email(props(null)).render(order(List.of(line("CB35", "Base", Map.of())), null, List.of()));

        assertThat(mail.attachments()).isEmpty();
        assertThat(mail.body()).doesNotContain("attached as");
    }

    /** A half-configured mailbox is said here, not left to the mail server's own stack trace. */
    @org.junit.jupiter.api.Test
    void repeatsWhatTheMailServerWouldRefuse() {
        SupplierOrderMailer mailer = org.mockito.Mockito.mock(SupplierOrderMailer.class);
        org.mockito.Mockito.when(mailer.problem()).thenReturn("set MAIL_USERNAME and MAIL_PASSWORD");

        EmailPreview mail = new SupplierOrderEmail(props(null), new DefaultResourceLoader(), mailer)
                .render(order(List.of(line("CB35", "Base", Map.of())), null, List.of()));

        assertThat(mail.missing()).containsExactly("set MAIL_USERNAME and MAIL_PASSWORD");
        assertThat(mail.body()).contains("CB35");   // still rendered: it can be read, not sent
    }

    @Test
    void fillsTheTemplateWithTheOrderAndItsEngraving(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("po.html");
        Files.writeString(template, "<p>Hi {{contact}}, PO {{poNumber}} of {{orderDate}}{{accountLine}}</p>"
                + "<table>{{lines}}</table>{{shipTo}}<i>{{shippingMethod}}{{shipAccountLine}}</i>"
                + "<em>{{dateNeeded}}</em>{{noteBlock}}<b>{{signature}}</b> {{fromEmail}}");
        Map<String, String> engraving = new LinkedHashMap<>();
        engraving.put("Line 1", "Coach of the Year");
        engraving.put("Line 2", "2026");

        EmailPreview mail = email(props("file:" + template)).render(
                order(List.of(line("CB35", "Optional Base", engraving)), "Use 16pt Avenir Book", List.of()));

        assertThat(mail.subject()).isEqualTo("Purchase Order 1046");
        assertThat(mail.to()).isEqualTo("orders@pacesetterawards.com");
        assertThat(mail.cc()).isEqualTo("shop@trophypartner.com");
        assertThat(mail.body())
                .contains("Hi Dana, PO 1046 of 22 Sep 2026 · Account TP-4412")
                .contains("<strong>CB35</strong>")
                .contains("Optional Base")
                .contains("Line 1:</span> Coach of the Year")
                .contains("Line 2:</span> 2026")
                .contains("<i>Standard on our UPS #4E4W93</i>")
                .contains("<em>5/11/2026</em>")
                .doesNotContain("36.99")          // the shop's PO lists what to make, not what it costs
                .contains("Jane Buyer<br>1 Main St<br>Wake Forest NC 27587<br>United States")
                .contains("Use 16pt Avenir Book")
                .contains("<b>Matt Gunn</b> orders@trophypartner.com");
        assertThat(mail.missing()).isEmpty();
        assertThat(mail.canSend()).isFalse();
    }

    /** The text is typed by a shopper: it has to arrive as characters, never as markup. */
    @Test
    void escapesWhatTheShopperTyped(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("po.html");
        Files.writeString(template, "<table>{{lines}}</table>");

        EmailPreview mail = email(props("file:" + template)).render(order(
                List.of(line("CB35", "Base", Map.of("Line 1", "<script>alert(1)</script> R&D <Champions>"))),
                null, List.of()));

        assertThat(mail.body()).doesNotContain("<script>")
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; R&amp;D &lt;Champions&gt;");
    }

    @Test
    void leavesOutTheNoteBlockWhenTheOrderHasNoNote(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("po.html");
        Files.writeString(template, "start{{noteBlock}}end");

        assertThat(email(props("file:" + template))
                .render(order(List.of(line("CB35", "Base", Map.of())), null, List.of())).body())
                .isEqualTo("startend");
    }

    /** Rendering never refuses: what would stop the send is reported, so the template can be read first. */
    @Test
    void saysWhatWouldStopTheSend(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("po.html");
        Files.writeString(template, "{{poNumber}}");
        SupplierOrderProperties noRecipient = new SupplierOrderProperties(false, null, null, null, null, null,
                null, "file:" + template, null, null, null, null);

        EmailPreview mail = email(noRecipient).render(order(List.of(line("CB35", "Base", Map.of())), null,
                List.of("Already sent to PaceSetter.")));

        assertThat(mail.body()).isEqualTo("1046");
        // The default subject is the shop's own wording, which PaceSetter's inbox rules expect.
        assertThat(mail.subject()).isEqualTo("TrophyPartner.com Order P.O. # 1046");
        assertThat(mail.missing()).containsExactly(
                "No recipient: set orders.pacesetter.to (PaceSetter's order mailbox).",
                "No sender: set orders.pacesetter.from to the mailbox PaceSetter knows.",
                "Already sent to PaceSetter.");
    }

    /**
     * The same message as plain text, which the email carries as its text/plain part and the Shopify
     * order action shows — an admin UI extension renders components, not HTML.
     */
    @Test
    void alsoRendersTheMessageAsText(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("po.html");
        Files.writeString(template, "<!-- notes for whoever edits this -->"
                + "<p>Hi {{contact}},</p><p>I&rsquo;d like to place an order:</p>"
                + "<table><tr><td>CB35</td><td>Base</td><td>2</td></tr></table>"
                + "<p>Ship by {{shippingMethod}}<br>to arrive by {{dateNeeded}}</p>");

        EmailPreview mail = email(props("file:" + template))
                .render(order(List.of(line("CB35", "Base", Map.of())), null, List.of()));

        // The blank line is the table ending: blocks keep one between them, however many the HTML had.
        assertThat(mail.text())
                .isEqualTo("""
                        Hi Dana,
                        I'd like to place an order:
                        CB35  ·  Base  ·  2

                        Ship by Standard
                        to arrive by 5/11/2026""");
        // The template's own instructions are not part of the email.
        assertThat(mail.text()).doesNotContain("notes for whoever edits this").doesNotContain("<");
    }

    /** The shipped template is the default, and it has to render without a configured file. */
    @Test
    void rendersTheTemplateTheAppShipsWith() {
        EmailPreview mail = email(props(null)).render(
                order(List.of(line("CB35", "Optional Base", Map.of())), null, List.of()));

        assertThat(mail.body())
                .contains("Hi Dana,")
                .contains("I&rsquo;d like to place an order")
                // The placeholders the comment documents are listed unbraced, so they stay readable
                // in the file that gets edited instead of being filled in like the rest.
                .contains("shipAccountLine \" on our UPS")
                .contains("CB35")
                .contains("P.O. number is <strong>1046</strong>")
                .contains("to arrive by 5/11/2026")
                .doesNotContain("{{").doesNotContain("}}");
    }
}
