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
        return new Line(partId, title, null, "PS9250", 2, 2, new BigDecimal("36.99"), "USD", personalization);
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
