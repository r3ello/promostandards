package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SupplierOrderService.Line;
import com.trophy.promostandards.sync.SupplierOrderService.Preview;
import com.trophy.promostandards.sync.SupplierOrderService.ShipTo;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds the email that would go to PaceSetter for one order: recipients from configuration, body
 * from the template file. Renders only — sending is a separate step, and a preview must be readable
 * long before anything is allowed to leave the server.
 *
 * <p>The template is plain HTML with {@code {{name}}} placeholders, read from disk on every render so
 * editing it (or pointing {@code orders.pacesetter.template} at another file) takes effect at once.
 * Everything substituted in is HTML-escaped: the text to engrave is typed by a shopper, and
 * "R&amp;D 2026 &lt;Champions&gt;" has to arrive as those characters, not as markup.
 */
@Component
public class SupplierOrderEmail {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    private final SupplierOrderProperties props;
    private final ResourceLoader resources;
    private final SupplierOrderMailer mailer;

    public SupplierOrderEmail(SupplierOrderProperties props, ResourceLoader resources,
                              SupplierOrderMailer mailer) {
        this.props = props;
        this.resources = resources;
        this.mailer = mailer;
    }

    /**
     * Addresses for one message. A null or blank field means "whatever is configured", so a caller
     * that overrides the recipient does not have to repeat the rest.
     */
    public record Recipients(String to, String cc, String bcc) {

        public static final Recipients CONFIGURED = new Recipients(null, null, null);

        private static String or(String override, String configured) {
            return override == null || override.isBlank() ? configured : override.trim();
        }
    }

    /**
     * The message, exactly as it would be sent.
     *
     * @param defaults the addresses configured on the server, whatever this render was asked for:
     *                 what the console compares against to warn that a recipient was changed
     * @param missing  what stops it going out as it is — no recipient, or the order itself not ready.
     *                 Rendering still happens: seeing the email is how the template is checked.
     * @param canSend  whether the app is allowed to send at all ({@code orders.pacesetter.enabled})
     */
    public record EmailPreview(String to, String cc, String bcc, String from, String replyTo,
                               String subject, String body, boolean canSend, Recipients defaults,
                               List<String> missing) {
    }

    public EmailPreview render(Preview order) {
        return render(order, Recipients.CONFIGURED);
    }

    /** @param recipients addresses for this one message; blank fields fall back to the configured ones */
    public EmailPreview render(Preview order, Recipients recipients) {
        Map<String, String> values = values(order);
        String to = Recipients.or(recipients.to(), props.to());
        String cc = Recipients.or(recipients.cc(), props.cc());
        String bcc = Recipients.or(recipients.bcc(), props.bcc());
        List<String> missing = new ArrayList<>();
        if (to == null || to.isBlank()) {
            missing.add("No recipient: set orders.pacesetter.to (PaceSetter's order mailbox).");
        }
        if (props.from() == null || props.from().isBlank()) {
            missing.add("No sender: set orders.pacesetter.from to the mailbox PaceSetter knows.");
        }
        // What the mail server would answer, asked before anything is sent: its own version arrives
        // as a stack trace after the click, and names neither the setting nor this app.
        String smtp = mailer.problem();
        if (smtp != null) {
            missing.add(smtp);
        }
        if (!order.isReady()) {
            missing.addAll(order.blocking());
        }
        return new EmailPreview(to, cc, bcc, props.from(), props.replyTo(),
                fill(props.subject(), values), fill(template(), values), props.isEnabled(),
                new Recipients(props.to(), props.cc(), props.bcc()), missing);
    }

    private String template() {
        Resource resource = resources.getResource(props.template());
        try (var in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the PaceSetter email template "
                    + props.template(), e);
        }
    }

    private Map<String, String> values(Preview order) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("poNumber", esc(order.poNumber()));
        v.put("orderName", esc(order.orderName()));
        v.put("orderDate", esc(date(order.createdAt())));
        v.put("accountNumber", esc(props.accountNumber()));
        v.put("accountLine", props.accountNumber() == null || props.accountNumber().isBlank()
                ? "" : " · Account " + esc(props.accountNumber()));
        v.put("lines", lines(order.lines()));
        v.put("shipTo", shipTo(order.shipTo()));
        v.put("shippingMethod", order.shippingMethod() == null ? "your usual method" : esc(order.shippingMethod()));
        v.put("contact", props.contact() == null || props.contact().isBlank() ? "there" : esc(props.contact()));
        // Never invented: an account number that is not the shop's would have the freight billed to a
        // stranger, so with none configured the sentence simply does not mention one.
        v.put("shipAccountLine", props.shipAccount() == null || props.shipAccount().isBlank()
                ? "" : " on our " + esc(props.shipAccount()));
        v.put("dateNeeded", order.dateNeeded() == null ? "as soon as possible" : esc(order.dateNeeded()));
        v.put("fromEmail", esc(props.from()));
        v.put("noteBlock", order.note() == null ? "" : """
                <h2 style="margin:0 0 6px;font-size:14px;">Notes from the order</h2>
                <p style="margin:0 0 20px;white-space:pre-wrap;">%s</p>""".formatted(esc(order.note())));
        v.put("signature", esc(props.signature()));
        return v;
    }

    /**
     * One row per line: item, description, quantity, and the text to engrave — what the shop's own POs
     * have always listed. No prices: PaceSetter prices the order from their own table and invoices it,
     * and a figure of ours in the PO is only something to argue about.
     */
    private String lines(List<Line> lines) {
        StringBuilder rows = new StringBuilder();
        for (Line line : lines) {
            String engraving = line.personalization().entrySet().stream()
                    .map(e -> "<div><span style=\"color:#616161;\">" + esc(e.getKey()) + ":</span> " + esc(e.getValue()) + "</div>")
                    .reduce("", String::concat);
            rows.append("""
                    <tr>
                      <td style="padding:8px;border-bottom:1px solid #e3e3e3;vertical-align:top;"><strong>%s</strong></td>
                      <td style="padding:8px;border-bottom:1px solid #e3e3e3;vertical-align:top;">%s</td>
                      <td style="padding:8px;border-bottom:1px solid #e3e3e3;text-align:right;vertical-align:top;">%d</td>
                      <td style="padding:8px;border-bottom:1px solid #e3e3e3;vertical-align:top;font-size:13px;">%s</td>
                    </tr>
                    """.formatted(esc(line.partId() == null ? "?" : line.partId()), esc(line.title()),
                    line.quantity(),
                    engraving.isEmpty() ? "<span style=\"color:#616161;\">none</span>" : engraving));
        }
        return rows.toString();
    }

    private String shipTo(ShipTo a) {
        if (a == null) {
            return "<p style=\"margin:0;color:#616161;\">No shipping address on the order.</p>";
        }
        String block = Stream.of(a.name(), a.company(), a.address1(), a.address2(),
                        Stream.of(a.city(), a.provinceCode() != null ? a.provinceCode() : a.province(), a.zip())
                                .filter(s -> s != null && !s.isBlank()).reduce((x, y) -> x + " " + y).orElse(null),
                        a.country(), a.phone())
                .filter(s -> s != null && !s.isBlank())
                .map(SupplierOrderEmail::esc)
                .reduce((x, y) -> x + "<br>" + y).orElse("");
        return "<p style=\"margin:0;\">" + block + "</p>";
    }

    private static String date(String iso) {
        try {
            return DATE.format(Instant.parse(iso));
        } catch (RuntimeException e) {
            return iso == null ? "" : iso;
        }
    }

    /** A placeholder with no value becomes empty: a template may name more than an order carries. */
    private static String fill(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            int close = open < 0 ? -1 : template.indexOf("}}", open + 2);
            if (open < 0 || close < 0) {
                out.append(template, i, template.length());
                break;
            }
            out.append(template, i, open).append(values.getOrDefault(template.substring(open + 2, close).trim(), ""));
            i = close + 2;
        }
        return out.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
