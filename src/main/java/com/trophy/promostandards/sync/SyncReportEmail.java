package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SyncPassReport;
import com.trophy.promostandards.sync.model.SyncPassReport.Line;
import org.springframework.web.util.HtmlUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the sync report: one scheduled pass, or a day of inventory passes. Pure — the passes come
 * in, subject and body go out — so what the email says is tested without a mailbox.
 *
 * <p>In English, like the console: it is the client's report. Every value is HTML-escaped (an error
 * text is whatever a server answered).
 */
final class SyncReportEmail {

    /** What goes out: the same message as HTML and as plain text. */
    record Message(String subject, String html, String text) {
    }

    /** Rows listed per table before "and N more": the console has the rest. */
    static final int LIST_LIMIT = 150;

    private static final Map<String, String> OUTCOME_LABELS = new LinkedHashMap<>();

    static {
        OUTCOME_LABELS.put("PUSHED", "updated");
        OUTCOME_LABELS.put("WOULD_PUSH", "would be updated");
        OUTCOME_LABELS.put("NEEDS_VARIANTS", "have new PaceSetter variants");
        OUTCOME_LABELS.put("UNCHANGED", "unchanged");
        OUTCOME_LABELS.put("BACKING_OFF", "waiting to retry after earlier failures");
        OUTCOME_LABELS.put("FAILED", "failed");
    }

    private final ZoneId zone;
    private final DateTimeFormatter dayTime;
    private final DateTimeFormatter time;

    SyncReportEmail(String timeZone) {
        ZoneId z;
        try {
            z = ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone);
        } catch (RuntimeException e) {
            z = ZoneId.of("UTC");
        }
        this.zone = z;
        this.dayTime = DateTimeFormatter.ofPattern("MMM d, h:mm a z", Locale.US).withZone(zone);
        this.time = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(zone);
    }

    /** One pass: every price pass, and an inventory pass that had failures. */
    Message pass(SyncPassReport r) {
        String what = "price".equals(r.job()) ? "Prices" : "Inventory";
        int updated = r.changed().size();
        String subject = "[PaceSetter sync] " + (r.dryRun() ? "[DRY RUN] " : "") + what + ": "
                + plural(updated, "product") + (r.dryRun() ? " would be updated" : " updated")
                + (r.failed().isEmpty() ? "" : ", " + r.failed().size() + " failed");

        Doc d = new Doc();
        d.p(what + " pass of " + dayTime.format(r.startedAt()) + " to " + time.format(r.finishedAt())
                + " (" + minutes(r.startedAt(), r.finishedAt()) + "): " + plural(r.products(), "product")
                + " checked, " + plural(r.variantsWritten(), "variant") + " written to Shopify.");
        if (r.dryRun()) {
            d.p("This was a dry run: nothing was written. The products below are what a real pass would update.");
        }
        d.list(outcomeLines(r.outcomes()));
        // A dry run writes nothing, so its variant count is always 0: the column would only mislead.
        if (r.dryRun()) {
            d.table("Would be updated", List.of("PaceSetter code"),
                    r.changed().stream().map(l -> List.of(l.productId())).toList());
        } else {
            d.table("Updated", List.of("PaceSetter code", "Variants"),
                    r.changed().stream().map(l -> List.of(l.productId(), String.valueOf(l.variants()))).toList());
        }
        d.table("Failed", List.of("PaceSetter code", "Error"),
                r.failed().stream().map(l -> List.of(l.productId(), nonNull(l.error()))).toList());
        d.table("Quantity discounts not republished after the price moved", List.of("PaceSetter code", "Why"),
                r.discountProblems().stream().map(l -> List.of(l.productId(), nonNull(l.error()))).toList());
        if (r.failed().isEmpty() && r.changed().isEmpty()) {
            d.p("Nothing changed at PaceSetter and nothing failed.");
        }
        d.footer();
        return new Message(subject, d.html(), d.text());
    }

    /** A day of inventory passes, in one email. */
    Message digest(List<SyncPassReport> passes) {
        Map<String, int[]> updated = new TreeMap<>();          // id -> {passes, variants}
        Map<String, String> lastError = new TreeMap<>();
        Map<String, Integer> failures = new TreeMap<>();
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        int variants = 0;
        boolean dry = false;
        for (SyncPassReport r : passes) {
            dry |= r.dryRun();
            variants += r.variantsWritten();
            r.outcomes().forEach((k, v) -> outcomes.merge(k, v, Integer::sum));
            for (Line l : r.changed()) {
                int[] t = updated.computeIfAbsent(l.productId(), k -> new int[2]);
                t[0]++;
                t[1] += l.variants();
            }
            for (Line l : r.failed()) {
                failures.merge(l.productId(), 1, Integer::sum);
                lastError.put(l.productId(), nonNull(l.error()));
            }
        }
        Instant from = passes.stream().map(SyncPassReport::startedAt).min(Instant::compareTo).orElse(Instant.now());
        Instant to = passes.stream().map(SyncPassReport::finishedAt).max(Instant::compareTo).orElse(Instant.now());
        String subject = "[PaceSetter sync] " + (dry ? "[DRY RUN] " : "") + "Inventory, daily summary: "
                + plural(updated.size(), "product") + (dry ? " would be updated" : " updated")
                + (failures.isEmpty() ? "" : ", " + failures.size() + " with failures");

        Doc d = new Doc();
        d.p(plural(passes.size(), "inventory pass") + " from " + dayTime.format(from) + " to " + dayTime.format(to)
                + ": " + plural(variants, "variant") + " written to Shopify.");
        if (dry) {
            d.p("Dry run: nothing was written. The products below are what real passes would update.");
        }
        d.list(outcomeLines(outcomes).stream().map(s -> s + " (counted once per pass)").toList());
        d.table(dry ? "Would be updated" : "Updated", List.of("PaceSetter code", "Passes", "Variants"),
                updated.entrySet().stream().map(e -> List.of(e.getKey(), String.valueOf(e.getValue()[0]),
                        String.valueOf(e.getValue()[1]))).toList());
        d.table("Failed", List.of("PaceSetter code", "Failed passes", "Last error"),
                failures.entrySet().stream().map(e -> List.of(e.getKey(), String.valueOf(e.getValue()),
                        lastError.getOrDefault(e.getKey(), ""))).toList());
        if (updated.isEmpty() && failures.isEmpty()) {
            d.p("No stock changed at PaceSetter and nothing failed.");
        }
        d.footer();
        return new Message(subject, d.html(), d.text());
    }

    private static List<String> outcomeLines(Map<String, Integer> outcomes) {
        List<String> lines = new ArrayList<>();
        OUTCOME_LABELS.forEach((key, label) -> {
            Integer n = outcomes.get(key);
            if (n != null && n > 0) {
                lines.add(n + " " + label);
            }
        });
        outcomes.forEach((key, n) -> {
            if (!OUTCOME_LABELS.containsKey(key) && n != null && n > 0) {
                lines.add(n + " " + key.toLowerCase(Locale.ROOT).replace('_', ' '));
            }
        });
        return lines;
    }

    private static String minutes(Instant from, Instant to) {
        long m = Math.max(0, Duration.between(from, to).toMinutes());
        return m < 1 ? "under a minute" : plural((int) m, "minute");
    }

    private static String plural(int n, String word) {
        return String.format(Locale.US, "%,d", n) + " " + word
                + (n == 1 ? "" : word.endsWith("s") ? "es" : "s");
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    /** The two renderings side by side, so they cannot drift apart. */
    private static final class Doc {
        private final StringBuilder html = new StringBuilder(
                "<div style=\"font-family:-apple-system,Segoe UI,Roboto,sans-serif;font-size:14px;color:#202223\">");
        private final StringBuilder text = new StringBuilder();

        void p(String s) {
            html.append("<p>").append(esc(s)).append("</p>");
            text.append(s).append("\n\n");
        }

        void list(List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            html.append("<ul>");
            for (String i : items) {
                html.append("<li>").append(esc(i)).append("</li>");
                text.append("- ").append(i).append('\n');
            }
            html.append("</ul>");
            text.append('\n');
        }

        void table(String title, List<String> head, List<List<String>> rows) {
            if (rows.isEmpty()) {
                return;
            }
            html.append("<h3 style=\"font-size:15px;margin:18px 0 6px\">").append(esc(title)).append(" (")
                    .append(rows.size()).append(")</h3>")
                    .append("<table style=\"border-collapse:collapse\"><tr>");
            for (String h : head) {
                html.append("<th style=\"text-align:left;padding:4px 12px 4px 0;border-bottom:1px solid #ccc\">")
                        .append(esc(h)).append("</th>");
            }
            html.append("</tr>");
            text.append(title).append(" (").append(rows.size()).append("):\n");
            int shown = 0;
            for (List<String> row : rows) {
                if (shown++ == LIST_LIMIT) {
                    break;
                }
                html.append("<tr>");
                for (int i = 0; i < row.size(); i++) {
                    String style = i == 0 ? "font-family:Consolas,monospace;" : "";
                    html.append("<td style=\"").append(style).append("padding:3px 12px 3px 0;vertical-align:top\">")
                            .append(esc(row.get(i))).append("</td>");
                }
                html.append("</tr>");
                text.append("  ").append(String.join("  ", row)).append('\n');
            }
            html.append("</table>");
            if (rows.size() > LIST_LIMIT) {
                String more = "… and " + (rows.size() - LIST_LIMIT) + " more.";
                html.append("<p>").append(esc(more)).append("</p>");
                text.append("  ").append(more).append('\n');
            }
            text.append('\n');
        }

        void footer() {
            String s = "Sent by the PaceSetter sync. The console's Scheduled sync panel shows the last pass of each job.";
            html.append("<p style=\"color:#6d7175;font-size:12px;margin-top:24px\">").append(esc(s)).append("</p>");
            text.append("--\n").append(s).append('\n');
        }

        String html() {
            return html + "</div>";
        }

        String text() {
            return text.toString().trim() + "\n";
        }

        private static String esc(String s) {
            return HtmlUtils.htmlEscape(s == null ? "" : s);
        }
    }
}
