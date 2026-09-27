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

    /**
     * Engraved pieces written out in the body; the rest are only in the attached sheet. An order can
     * hold 500 pieces, and a body that long is clipped by the mail client (Gmail cuts at ~102 KB)
     * without saying so — the worst way to lose an engraving.
     */
    static final int BODY_PIECE_LIMIT = 25;

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
    /**
     * @param text the same message as plain text, derived from {@code body}. Two callers need it: the
     *             email itself carries it as its text/plain alternative, and the Shopify admin action
     *             shows it — an admin UI extension can render components, not HTML, so without this
     *             the only place the actual message could be read was this app's own console.
     */
    public record EmailPreview(String to, String cc, String bcc, String from, String replyTo,
                               String subject, String body, String text, boolean canSend,
                               Recipients defaults, List<String> missing, List<Attachment> attachments) {
    }

    /**
     * A file sent with the message. Today only the engraving sheet: every piece of every line, one row
     * per engraved line, as CSV — it opens in Excel, sorts, and is never clipped the way a body is.
     */
    public record Attachment(String filename, String contentType, String content) {
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
        String body = fill(template(), values);
        Attachment sheet = EngravingSheet.of(order);
        return new EmailPreview(to, cc, bcc, props.from(), props.replyTo(),
                fill(props.subject(), values), body, asText(body), props.isEnabled(),
                new Recipients(props.to(), props.cc(), props.bcc()), missing,
                sheet == null ? List.of() : List.of(sheet));
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
        Map<String, String> shared = sharedArtwork(order.lines());
        v.put("artworkBlock", shared.isEmpty() ? "" : "<div style=\"margin:0 0 16px;\">"
                + "<div><strong>Artwork for every piece in this order</strong></div>" + artwork(shared) + "</div>");
        v.put("lines", lines(order.lines(), !shared.isEmpty()));
        int pieces = EngravingSheet.pieces(order);
        v.put("attachmentLine", pieces == 0 ? "" : "<p style=\"margin:0 0 16px;\">The engraving for all "
                + pieces + " piece" + (pieces == 1 ? "" : "s") + " &mdash; each line with its font &mdash; "
                + "is also attached as <strong>" + esc(EngravingSheet.filename(order)) + "</strong>.</p>");
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
    private String lines(List<Line> lines, boolean artworkShown) {
        StringBuilder rows = new StringBuilder();
        int[] budget = {BODY_PIECE_LIMIT};
        for (Line line : lines) {
            String engraving = line.personalization().entrySet().stream()
                    .map(e -> "<div><span style=\"color:#616161;\">" + esc(e.getKey()) + ":</span> " + esc(e.getValue()) + "</div>")
                    .reduce("", String::concat)
                    + customization(line.customization(), budget, artworkShown);
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

    /**
     * The logo and the preview when every customized line carries the same ones — the usual case: one
     * logo and one preview per order, whatever the text on each piece. Then they are written once,
     * above the table, instead of on every line. Empty when lines differ (each keeps its own) or none
     * has any.
     */
    static Map<String, String> sharedArtwork(List<Line> lines) {
        List<Map<String, String>> all = lines.stream().filter(l -> l.customization() != null)
                .map(l -> l.customization().artwork()).distinct().toList();
        return all.size() == 1 ? all.get(0) : Map.of();
    }

    /** As links: 500 images do not travel in an email, and PaceSetter opens the one it needs. */
    private static String artwork(Map<String, String> artwork) {
        StringBuilder out = new StringBuilder();
        artwork.forEach((key, value) -> out.append("<div><span style=\"color:#616161;\">")
                .append(esc(TrophyItem.label(key))).append(":</span> ")
                .append(value.startsWith("http")
                        ? "<a href=\"" + esc(value) + "\" style=\"color:#005bd3;\">" + esc(value) + "</a>"
                        : esc(value))
                .append("</div>"));
        return out.toString();
    }

    /**
     * The line's artwork (unless the whole order shares it and it is already above the table), the
     * fonts it needs, and each piece's lines with their font — until the body's budget runs out, after
     * which the attached sheet carries the rest and the body says so.
     */
    private static String customization(TrophyItem item, int[] budget, boolean artworkShown) {
        if (item == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(artworkShown ? "" : artwork(item.artwork()));
        if (!item.fonts().isEmpty()) {
            out.append("<div><span style=\"color:#616161;\">Fonts:</span> ")
                    .append(esc(String.join(", ", item.fonts()))).append("</div>");
        }
        int shown = 0;
        for (TrophyItem.Piece piece : item.pieces()) {
            if (budget[0] <= 0) {
                break;
            }
            budget[0]--;
            shown++;
            out.append("<div style=\"margin-top:6px;\"><div><strong>Piece ").append(piece.number()).append("</strong>");
            if (piece.texts().isEmpty()) {
                out.append(" <span style=\"color:#616161;\">no text</span>");
            }
            out.append("</div>");
            for (TrophyItem.Text t : piece.texts()) {
                out.append("<div>").append(esc(t.label())).append(": ").append(esc(t.text().strip()));
                if (t.font() != null) {
                    out.append(" <span style=\"color:#616161;\">(").append(esc(t.font())).append(")</span>");
                }
                out.append("</div>");
            }
            out.append("</div>");
        }
        int left = item.pieces().size() - shown;
        if (left > 0) {
            out.append("<div style=\"margin-top:6px;\"><em>").append(shown == 0 ? "All " + left : "The other " + left)
                    .append(" piece").append(left == 1 ? " is" : "s are")
                    .append(" in the attached engraving sheet.</em></div>");
        }
        return out.toString();
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

    /**
     * The message as plain text. Derived from the rendered HTML rather than kept as a second template:
     * two templates drift, and the one that drifts is always the one nobody reads.
     *
     * <p>Comments go (the template's own instructions are not part of the email), a table row becomes
     * a line with its cells separated, every other block tag becomes a line break, entities come back
     * to characters, and runs of blank lines collapse.
     */
    static String asText(String html) {
        String text = html.replaceAll("(?s)<!--.*?-->", "")
                .replaceAll("(?s)<(script|style)\\b.*?</\\1>", "")
                .replaceAll("(?i)</t[dh]>\\s*<t[dh][^>]*>", "  ·  ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|h1|h2|h3|li|address|table|thead|tbody)>", "\n")
                .replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&rsquo;", "'").replace("&mdash;", "—")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");
        StringBuilder out = new StringBuilder();
        int blank = 0;
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                blank++;
                continue;
            }
            // One blank line between blocks, however many the HTML had.
            if (blank > 0 && !out.isEmpty()) {
                out.append("\n");
            }
            blank = 0;
            out.append(trimmed).append("\n");
        }
        return out.toString().strip();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
