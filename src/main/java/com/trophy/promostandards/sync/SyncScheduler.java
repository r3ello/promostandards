package com.trophy.promostandards.sync;

import com.trophy.promostandards.db.OrderStore;
import com.trophy.promostandards.db.SyncStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cron-driven inventory and price refresh for every imported product, mirroring PromoSync's
 * automation. All jobs no-op unless {@code sync.schedule.enabled=true}, so enabling scheduling in
 * the context (or running tests) never makes Shopify calls by accident. Cron expressions come from
 * {@code sync.schedule.*}. Per-product failures are logged and skipped so one bad product does not
 * halt the batch.
 */
@Component
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final ShopifySyncService sync;
    private final OrderSyncService orderSync;
    private final SyncProperties props;
    private final ObjectProvider<SyncStateStore> syncStates;
    private final ObjectProvider<OrderStore> orderStores;

    /** Job name the order watermark is stored under. */
    private static final String ORDER_JOB = "orders";

    /** In-memory fallback watermark, used only when no database is configured. */
    private volatile Instant orderCursor;

    public SyncScheduler(ShopifySyncService sync, OrderSyncService orderSync, SyncProperties props,
                         ObjectProvider<SyncStateStore> syncStates, ObjectProvider<OrderStore> orderStores) {
        this.sync = sync;
        this.orderSync = orderSync;
        this.props = props;
        this.syncStates = syncStates;
        this.orderStores = orderStores;
    }

    @Scheduled(cron = "${sync.schedule.inventory-cron}")
    public void refreshInventory() {
        if (!enabled()) {
            return;
        }
        runForEach(SyncStateStore.Kind.INVENTORY);
    }

    @Scheduled(cron = "${sync.schedule.price-cron}")
    public void refreshPrices() {
        if (!enabled()) {
            return;
        }
        runForEach(SyncStateStore.Kind.PRICE);
    }

    /**
     * Order sync over the window since the last successful run.
     *
     * <p>The watermark is persisted when a database is configured. Held only in memory — as it was —
     * every restart rewinds it a full day and re-processes shipments that were already fulfilled.
     * The shipment-level dedupe in {@link OrderSyncService} is what makes that harmless; this makes
     * it rare.
     */
    @Scheduled(cron = "${sync.schedule.order-cron}")
    public void refreshOrders() {
        if (!enabled()) {
            return;
        }
        Instant since = orderWindowStart();
        Instant runAt = Instant.now();
        try {
            orderSync.syncOrders(since);
            advanceOrderCursor(runAt); // only on success, so a failure re-tries the same window
        } catch (RuntimeException e) {
            log.warn("Scheduled order sync failed for window since {}: {}", since, e.getMessage());
        }
    }

    private Instant orderWindowStart() {
        OrderStore store = orderStores.getIfAvailable();
        if (store != null) {
            try {
                Optional<Instant> stored = store.watermark(ORDER_JOB);
                if (stored.isPresent()) {
                    return stored.get();
                }
            } catch (RuntimeException e) {
                log.warn("Could not read the order watermark, using the in-memory one: {}", e.getMessage());
            }
        }
        return orderCursor != null ? orderCursor : Instant.now().minus(Duration.ofDays(1));
    }

    private void advanceOrderCursor(Instant runAt) {
        orderCursor = runAt;
        OrderStore store = orderStores.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            store.saveWatermark(ORDER_JOB, runAt);
        } catch (RuntimeException e) {
            log.warn("Could not persist the order watermark: {}", e.getMessage());
        }
    }

    /**
     * Walks every imported product but pushes only what actually changed.
     *
     * <p>The supplier still has to be read per product — there is no "what changed" endpoint for
     * inventory or pricing — but products whose values match the last push cost no Shopify calls at
     * all. In steady state that is the difference between ~2,800 mutations per run and none.
     *
     * <p>Runs are summarised by outcome rather than a bare success count, because "1,278 unchanged"
     * and "1,278 pushed" are the same number of successes and completely different situations.
     */
    private void runForEach(SyncStateStore.Kind kind) {
        boolean dryRun = dryRun();
        List<String> productIds = sync.listImportedProductIds();
        String label = kind.value() + (dryRun ? " (dry run)" : "");
        log.info("Scheduled {} refresh over {} imported products", label, productIds.size());

        Map<ShopifySyncService.Outcome, Integer> tally = new EnumMap<>(ShopifySyncService.Outcome.class);
        Instant startedAt = Instant.now();
        int variantsWritten = 0;
        for (String productId : productIds) {
            ShopifySyncService.RefreshResult result;
            try {
                result = sync.refresh(productId, kind, dryRun, false);
            } catch (RuntimeException e) {
                // refresh() reports push failures rather than throwing; anything that escapes is a
                // read failure (supplier down, product gone). One bad product never halts the batch.
                log.warn("Scheduled {} refresh failed for {}: {}", label, productId, e.getMessage());
                tally.merge(ShopifySyncService.Outcome.FAILED, 1, Integer::sum);
                continue;
            }
            tally.merge(result.outcome(), 1, Integer::sum);
            variantsWritten += result.updated();
            if (result.outcome() == ShopifySyncService.Outcome.FAILED) {
                log.warn("Scheduled {} push failed for {}: {}", label, productId, result.error());
            }
        }

        log.info("Scheduled {} refresh complete in {}s: {} ({} variants written)",
                label, Duration.between(startedAt, Instant.now()).toSeconds(), tally, variantsWritten);
        recordRun(kind.value(), startedAt, productIds.size(), tally);
    }

    /** Keeps a row per run so the incremental behaviour can be inspected after the fact, not just in logs. */
    private void recordRun(String job, Instant startedAt, int processed,
                           Map<ShopifySyncService.Outcome, Integer> tally) {
        SyncStateStore store = syncStates.getIfAvailable();
        if (store == null) {
            return;
        }
        int pushed = tally.getOrDefault(ShopifySyncService.Outcome.PUSHED, 0);
        int failed = tally.getOrDefault(ShopifySyncService.Outcome.FAILED, 0);
        int skipped = processed - pushed - failed;
        try {
            store.recordRun(job, startedAt, processed, pushed, failed, skipped);
        } catch (RuntimeException e) {
            log.debug("Could not record the sync run: {}", e.getMessage());
        }
    }

    private boolean dryRun() {
        return props.schedule() != null && props.schedule().dryRun();
    }

    private boolean enabled() {
        return props.schedule() != null && props.schedule().enabled();
    }
}
