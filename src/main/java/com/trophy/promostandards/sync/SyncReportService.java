package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SyncReportEmail.Message;
import com.trophy.promostandards.sync.model.SyncPassReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Emails what the scheduled syncs did ({@code sync.report.*}, off by default).
 *
 * <ul>
 *   <li><b>Prices</b> — every pass, as it finishes: one a night, and a moved price is worth seeing.</li>
 *   <li><b>Inventory</b> — a pass on its own only when something failed (or every pass, with
 *       {@code inventory-every-pass}); all passes together in a daily digest
 *       ({@code inventory-digest-cron}).</li>
 * </ul>
 *
 * <p>Nothing here can fail a sync: the pass has done its work before it is announced, and a mail
 * server that refuses is logged, not thrown. The digest is held in memory, so a restart sends the
 * passes since then only — what was lost is in the log and in {@code sync_run}.
 */
@Service
public class SyncReportService {

    private static final Logger log = LoggerFactory.getLogger(SyncReportService.class);

    private final SyncReportProperties props;
    private final SupplierOrderMailer mailer;
    private final SyncReportEmail email;
    private final List<SyncPassReport> inventoryPasses = new ArrayList<>();

    public SyncReportService(SyncReportProperties props, SupplierOrderMailer mailer) {
        this.props = props;
        this.mailer = mailer;
        this.email = new SyncReportEmail(props.timeZone());
    }

    @EventListener
    public void passFinished(SyncPassReport report) {
        if (!props.enabled()) {
            return;
        }
        if ("price".equals(report.job())) {
            send(email.pass(report));
            return;
        }
        if ("inventory".equals(report.job())) {
            synchronized (inventoryPasses) {
                inventoryPasses.add(report);
            }
            if (props.inventoryEveryPass() || !report.failed().isEmpty()) {
                send(email.pass(report));
            }
        }
    }

    /** The day's inventory passes in one email; nothing is sent for a day with no pass. */
    @Scheduled(cron = "${sync.report.inventory-digest-cron:0 0 7 * * *}",
            zone = "${sync.report.time-zone:America/New_York}")
    public void sendInventoryDigest() {
        if (!props.enabled()) {
            return;
        }
        List<SyncPassReport> day;
        synchronized (inventoryPasses) {
            if (inventoryPasses.isEmpty()) {
                return;
            }
            day = List.copyOf(inventoryPasses);
            inventoryPasses.clear();
        }
        send(email.digest(day));
    }

    /** Passes waiting for the next digest (for tests and the log). */
    int pendingInventoryPasses() {
        synchronized (inventoryPasses) {
            return inventoryPasses.size();
        }
    }

    private void send(Message message) {
        if (props.to() == null || props.to().isBlank()) {
            log.warn("Sync report not sent: sync.report.to (SYNC_REPORT_TO) is empty — {}", message.subject());
            return;
        }
        try {
            mailer.send(props.from(), props.to(), props.cc(), message.subject(), message.text(), message.html());
        } catch (RuntimeException e) {
            log.warn("Sync report not sent ({}): {}", message.subject(), e.getMessage());
        }
    }
}
