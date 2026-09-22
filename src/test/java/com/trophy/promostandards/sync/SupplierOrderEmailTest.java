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
        return new Preview("gid://shopify/Order/7291179761758", "#1046", "1046", "2026-09-22T16:48:24Z",
                true, "PAID", "UNFULFILLED", note, ADDRESS, "Standard", lines, List.of(), blocking,
                List.of(), null);
    }

    private static Line line(String partId, String title, Map<String, String> personalization) {
        return new Line(partId, title, null, "PS9250", 2, 2, new BigDecimal("36.99"), "USD", personalization);
    }

    private static SupplierOrderProperties props(String template) {
        return new SupplierOrderProperties(false, "orders@pacesetterawards.com", "shop@trophypartner.com",
                null, "orders@trophypartner.com", null, "Purchase Order {{poNumber}}", template, "TP-4412",
                "TrophyPartner");
    }

    private static SupplierOrderEmail email(SupplierOrderProperties props) {
        return new SupplierOrderEmail(props, new DefaultResourceLoader());
    }

    @Test
    void fillsTheTemplateWithTheOrderAndItsEngraving(@TempDir Path dir) throws Exception {
        Path template = dir.resolve("po.html");
        Files.writeString(template, "<p>PO {{poNumber}} of {{orderDate}}{{accountLine}}</p>"
                + "<table>{{lines}}</table>{{shipTo}}<i>{{shippingMethod}}</i>{{noteBlock}}<b>{{signature}}</b>");
        Map<String, String> engraving = new LinkedHashMap<>();
        engraving.put("Line 1", "Coach of the Year");
        engraving.put("Line 2", "2026");

        EmailPreview mail = email(props("file:" + template)).render(
                order(List.of(line("CB35", "Optional Base", engraving)), "Use 16pt Avenir Book", List.of()));

        assertThat(mail.subject()).isEqualTo("Purchase Order 1046");
        assertThat(mail.to()).isEqualTo("orders@pacesetterawards.com");
        assertThat(mail.cc()).isEqualTo("shop@trophypartner.com");
        assertThat(mail.body())
                .contains("PO 1046 of 22 Sep 2026 · Account TP-4412")
                .contains("<strong>CB35</strong>")
                .contains("Optional Base")
                .contains("Line 1:</span> Coach of the Year")
                .contains("Line 2:</span> 2026")
                .contains("USD 36.99")
                .contains("Jane Buyer<br>1 Main St<br>Wake Forest NC 27587<br>United States")
                .contains("<i>Standard</i>")
                .contains("Use 16pt Avenir Book")
                .contains("<b>TrophyPartner</b>");
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
                null, "file:" + template, null, null);

        EmailPreview mail = email(noRecipient).render(order(List.of(line("CB35", "Base", Map.of())), null,
                List.of("Already sent to PaceSetter.")));

        assertThat(mail.body()).isEqualTo("1046");
        assertThat(mail.subject()).isEqualTo("Purchase Order 1046");  // the default subject
        assertThat(mail.missing()).containsExactly(
                "No recipient: set orders.pacesetter.to (PaceSetter's order mailbox).",
                "No sender: set orders.pacesetter.from to the mailbox PaceSetter knows.",
                "Already sent to PaceSetter.");
    }

    /** The shipped template is the default, and it has to render without a configured file. */
    @Test
    void rendersTheTemplateTheAppShipsWith() {
        EmailPreview mail = email(props(null)).render(
                order(List.of(line("CB35", "Optional Base", Map.of())), null, List.of()));

        assertThat(mail.body()).contains("Purchase Order 1046").contains("CB35")
                .doesNotContain("{{").doesNotContain("}}");
    }
}
