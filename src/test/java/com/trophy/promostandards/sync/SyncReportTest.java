package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SyncReportEmail.Message;
import com.trophy.promostandards.sync.model.SyncPassReport;
import com.trophy.promostandards.sync.model.SyncPassReport.Line;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The sync report: what it says about a pass (and a day of them), and when it is sent at all.
 */
class SyncReportTest {

    private static final Instant START = Instant.parse("2026-10-17T07:00:00Z");   // 03:00 in New York

    private static SyncPassReport pass(String job, boolean dryRun, List<Line> changed, List<Line> failed) {
        return new SyncPassReport(job, dryRun, START, START.plusSeconds(41 * 60), 724,
                changed.stream().mapToInt(Line::variants).sum(),
                Map.of("PUSHED", changed.size(), "UNCHANGED", 724 - changed.size() - failed.size(),
                        "FAILED", failed.size()),
                changed, failed, List.of());
    }

    private final SyncReportEmail email = new SyncReportEmail("America/New_York");

    @Test
    void aPricePassSaysWhatChangedWhatFailedAndWhen() {
        Message m = email.pass(pass("price", false,
                List.of(new Line("GI307", 3, null), new Line("CM373BS", 10, null)),
                List.of(new Line("CD1268", 0, "getConfigurationAndPricing returned no configuration"))));

        assertThat(m.subject()).isEqualTo("[PaceSetter sync] Prices: 2 products updated, 1 failed");
        assertThat(m.text())
                .contains("Prices pass of Oct 17, 3:00 AM EDT to 3:41 AM (41 minutes): 724 products checked, "
                        + "13 variants written to Shopify.")
                .contains("- 2 updated").contains("- 721 unchanged").contains("- 1 failed")
                .contains("Updated (2):").contains("GI307  3")
                .contains("Failed (1):").contains("CD1268  getConfigurationAndPricing returned no configuration");
        assertThat(m.html()).contains("<td style=\"font-family:Consolas,monospace;padding:3px 12px 3px 0;"
                + "vertical-align:top\">GI307</td>");
    }

    @Test
    void aDryRunSaysNothingWasWritten() {
        Message m = email.pass(pass("price", true, List.of(new Line("GI307", 0, null)), List.of()));

        assertThat(m.subject()).isEqualTo("[PaceSetter sync] [DRY RUN] Prices: 1 product would be updated");
        assertThat(m.text()).contains("This was a dry run: nothing was written").contains("Would be updated (1):")
                .contains("  GI307\n").doesNotContain("GI307  0");
    }

    @Test
    void aQuietPassSaysSo() {
        Message m = email.pass(pass("price", false, List.of(), List.of()));

        assertThat(m.subject()).isEqualTo("[PaceSetter sync] Prices: 0 products updated");
        assertThat(m.text()).contains("Nothing changed at PaceSetter and nothing failed.");
    }

    /** An error text is whatever a server answered: it is escaped, never rendered. */
    @Test
    void everyValueIsEscaped() {
        Message m = email.pass(pass("price", false, List.of(),
                List.of(new Line("X1", 0, "<script>alert(1)</script>"))));

        assertThat(m.html()).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    void aLongListIsCutAndSaysHowManyMore() {
        List<Line> many = IntStream.range(0, SyncReportEmail.LIST_LIMIT + 5)
                .mapToObj(i -> new Line("ID" + i, 1, null)).toList();
        Message m = email.pass(pass("price", false, many, List.of()));

        assertThat(m.text()).contains("… and 5 more.");
    }

    @Test
    void theDailyDigestAddsUpTheDaysInventoryPasses() {
        Message m = email.digest(List.of(
                pass("inventory", false, List.of(new Line("GI307", 2, null)), List.of()),
                pass("inventory", false, List.of(new Line("GI307", 1, null), new Line("CM373BS", 4, null)),
                        List.of(new Line("CD1268", 0, "ProductID not found")))));

        assertThat(m.subject()).isEqualTo("[PaceSetter sync] Inventory, daily summary: 2 products updated, "
                + "1 with failures");
        assertThat(m.text()).startsWith("2 inventory passes from Oct 17, 3:00 AM EDT")
                .contains("Updated (2):").contains("CM373BS  1  4").contains("GI307  2  3")
                .contains("CD1268  1  ProductID not found");
    }

    // --- when it is sent ---------------------------------------------------------------

    private final SupplierOrderMailer mailer = mock(SupplierOrderMailer.class);

    private SyncReportService service(boolean enabled, String to, boolean everyPass) {
        return new SyncReportService(new SyncReportProperties(enabled, to, "", "", "0 0 7 * * *", everyPass,
                "America/New_York"), mailer);
    }

    @Test
    void everyPricePassIsSent() {
        service(true, "shop@example.test", false).passFinished(pass("price", false, List.of(), List.of()));

        verify(mailer).send(eq(""), eq("shop@example.test"), eq(""), contains("Prices: 0 products updated"),
                anyString(), anyString());
    }

    /** Twelve inventory emails a day saying "nothing changed" is noise: only a failed pass goes alone. */
    @Test
    void anInventoryPassIsSentAloneOnlyWhenSomethingFailed() {
        SyncReportService reports = service(true, "shop@example.test", false);

        reports.passFinished(pass("inventory", false, List.of(new Line("GI307", 2, null)), List.of()));
        verify(mailer, never()).send(any(), any(), any(), any(), any(), any());

        reports.passFinished(pass("inventory", false, List.of(),
                List.of(new Line("CD1268", 0, "ProductID not found"))));
        verify(mailer).send(any(), any(), any(), contains("Inventory: 0 products updated, 1 failed"), any(), any());
        assertThat(reports.pendingInventoryPasses()).isEqualTo(2);
    }

    @Test
    void theDigestGoesOutOnceAndStartsTheNextDayEmpty() {
        SyncReportService reports = service(true, "shop@example.test", false);
        reports.passFinished(pass("inventory", false, List.of(new Line("GI307", 2, null)), List.of()));

        reports.sendInventoryDigest();
        reports.sendInventoryDigest();       // nothing new since: nothing sent

        verify(mailer, times(1)).send(any(), any(), any(), contains("daily summary"), any(), any());
        assertThat(reports.pendingInventoryPasses()).isZero();
    }

    @Test
    void everyInventoryPassCanBeAskedFor() {
        service(true, "shop@example.test", true)
                .passFinished(pass("inventory", false, List.of(new Line("GI307", 2, null)), List.of()));

        verify(mailer).send(any(), any(), any(), contains("Inventory: 1 product updated"), any(), any());
    }

    @Test
    void nothingIsSentWhenOffOrWithNoRecipient() {
        service(false, "shop@example.test", false).passFinished(pass("price", false, List.of(), List.of()));
        service(true, " ", false).passFinished(pass("price", false, List.of(), List.of()));

        verify(mailer, never()).send(any(), any(), any(), any(), any(), any());
    }

    /** The pass has done its work by then: a mail server that refuses is logged, never thrown. */
    @Test
    void aRefusedEmailNeverFailsThePass() {
        doThrow(new IllegalStateException("No SMTP server configured")).when(mailer)
                .send(any(), any(), any(), any(), any(), any());

        service(true, "shop@example.test", false).passFinished(pass("price", false, List.of(), List.of()));
    }
}
