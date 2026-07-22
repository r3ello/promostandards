package com.trophy.promostandards.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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

    /** Watermark for incremental order sync; advances each run. */
    private volatile Instant orderCursor;

    public SyncScheduler(ShopifySyncService sync, OrderSyncService orderSync, SyncProperties props) {
        this.sync = sync;
        this.orderSync = orderSync;
        this.props = props;
    }

    @Scheduled(cron = "${sync.schedule.inventory-cron}")
    public void refreshInventory() {
        if (!enabled()) {
            return;
        }
        runForEach("inventory", sync::syncInventory);
    }

    @Scheduled(cron = "${sync.schedule.price-cron}")
    public void refreshPrices() {
        if (!enabled()) {
            return;
        }
        runForEach("pricing", sync::syncPricing);
    }

    @Scheduled(cron = "${sync.schedule.order-cron}")
    public void refreshOrders() {
        if (!enabled()) {
            return;
        }
        Instant since = orderCursor != null ? orderCursor : Instant.now().minus(Duration.ofDays(1));
        Instant runAt = Instant.now();
        try {
            orderSync.syncOrders(since);
            orderCursor = runAt; // only advance on success, so a failure re-tries the same window
        } catch (RuntimeException e) {
            log.warn("Scheduled order sync failed for window since {}: {}", since, e.getMessage());
        }
    }

    private void runForEach(String label, java.util.function.ToIntFunction<String> op) {
        List<String> productIds = sync.listImportedProductIds();
        log.info("Scheduled {} refresh for {} imported products", label, productIds.size());
        int ok = 0;
        for (String productId : productIds) {
            try {
                op.applyAsInt(productId);
                ok++;
            } catch (RuntimeException e) {
                log.warn("Scheduled {} refresh failed for {}: {}", label, productId, e.getMessage());
            }
        }
        log.info("Scheduled {} refresh complete: {}/{} succeeded", label, ok, productIds.size());
    }

    private boolean enabled() {
        return props.schedule() != null && props.schedule().enabled();
    }
}
