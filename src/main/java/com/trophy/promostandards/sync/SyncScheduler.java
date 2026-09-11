package com.trophy.promostandards.sync;

import com.trophy.promostandards.db.OrderStore;
import com.trophy.promostandards.db.SyncStateStore;
import com.trophy.promostandards.discount.DiscountSyncService;
import com.trophy.promostandards.discount.DiscountSyncService.DiscountResult;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final CatalogSummaryService catalog;
    private final DiscountSyncService discounts;
    private final SyncProperties props;
    private final ObjectProvider<SyncStateStore> syncStates;
    private final ObjectProvider<OrderStore> orderStores;

    /** Job name the order watermark is stored under. */
    private static final String ORDER_JOB = "orders";

    /** In-memory fallback watermark, used only when no database is configured. */
    private volatile Instant orderCursor;

    /** What each job did last, and whether one is running right now — see {@link #status()}. */
    private final Map<String, JobStatus> lastPasses = new ConcurrentHashMap<>();

    /** Jobs with a pass in flight. One pass per job at a time, whoever started it. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /**
     * Runs manually triggered passes off the request thread — a pass takes the better part of an
     * hour, far longer than any HTTP client will wait. Single-threaded on purpose: two passes at
     * once would double the load on the supplier for no gain.
     */
    private final ExecutorService passes = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "sync-pass");
        thread.setDaemon(true);
        return thread;
    });

    public SyncScheduler(ShopifySyncService sync, OrderSyncService orderSync,
                         CatalogSummaryService catalog, DiscountSyncService discounts,
                         SyncProperties props,
                         ObjectProvider<SyncStateStore> syncStates, ObjectProvider<OrderStore> orderStores) {
        this.sync = sync;
        this.orderSync = orderSync;
        this.catalog = catalog;
        this.discounts = discounts;
        this.props = props;
        this.syncStates = syncStates;
        this.orderStores = orderStores;
    }

    /**
     * What one job is doing, or did last.
     *
     * @param running    a pass is in flight right now; the figures below are the previous one's
     * @param startedAt  when the reported pass started
     * @param seconds    how long it took — the number that decides whether the cron interval is
     *                   long enough for the pass to finish before it fires again
     * @param products   ids the pass walked, after dropping what the supplier no longer sells
     * @param outcomes   how those ids ended up (PUSHED, UNCHANGED, WOULD_PUSH, FAILED, …)
     */
    public record JobStatus(String job, boolean running, Instant startedAt, long seconds,
                            int products, int variantsWritten, Map<String, Integer> outcomes) {
    }

    /**
     * @param enabled whether the cron jobs run at all ({@code sync.schedule.enabled})
     * @param dryRun  whether they evaluate without writing anything to Shopify
     * @param crons   the configured cron expression per job
     * @param jobs    the last (or current) pass per job — empty until one has started
     */
    public record ScheduleStatus(boolean enabled, boolean dryRun, Map<String, String> crons,
                                 List<JobStatus> jobs) {
    }

    /**
     * The scheduler's own state, so "is the automation on, and what did it just do?" is a question
     * the console can answer. On the server there is no terminal to read the log in, and a pass in
     * flight writes nothing anywhere until it finishes — {@code running} is the only thing that
     * distinguishes a long pass from a scheduler that never started.
     */
    public ScheduleStatus status() {
        SyncProperties.Schedule schedule = props.schedule();
        Map<String, String> crons = new LinkedHashMap<>();
        if (schedule != null) {
            crons.put(SyncStateStore.Kind.INVENTORY.value(), schedule.inventoryCron());
            crons.put(SyncStateStore.Kind.PRICE.value(), schedule.priceCron());
            crons.put(ORDER_JOB, schedule.orderCron());
        }
        List<JobStatus> jobs = new ArrayList<>();
        for (String job : crons.keySet()) {
            JobStatus status = lastPasses.get(job);
            if (status != null) {
                jobs.add(status);
            }
        }
        return new ScheduleStatus(enabled(), dryRun(), crons, jobs);
    }

    @Scheduled(cron = "${sync.schedule.inventory-cron}")
    public void refreshInventory() {
        if (!enabled()) {
            return;
        }
        runForEach(SyncStateStore.Kind.INVENTORY, dryRun());
    }

    @Scheduled(cron = "${sync.schedule.price-cron}")
    public void refreshPrices() {
        if (!enabled()) {
            return;
        }
        runForEach(SyncStateStore.Kind.PRICE, dryRun());
    }

    /**
     * Starts a pass now, in the background, whether or not the cron jobs are enabled.
     *
     * <p>This is how the automation gets proven before it is trusted to run on its own: a dry run
     * on demand reports what a real pass would push and how long it takes — the number that decides
     * the cron interval — instead of waiting for the next firing to find out. A job already running
     * is left alone rather than doubled up; the returned status says so.
     */
    public ScheduleStatus runNow(SyncStateStore.Kind kind, boolean dryRun) {
        if (running.contains(kind.value())) {
            log.info("A {} pass is already running; not starting another", kind.value());
            return status();
        }
        passes.submit(() -> runForEach(kind, dryRun));
        return status();
    }

    @PreDestroy
    void shutdown() {
        passes.shutdownNow();
    }

    /**
     * Order sync over the window since the last successful run.
     *
     * <p>The watermark is persisted when a database is configured. Held only in memory — as it was —
     * every restart rewinds it a full day and re-processes shipments that were already fulfilled.
     * The shipment-level dedupe in {@link OrderSyncService} is what makes that harmless; this makes
     * it rare.
     *
     * <p>A dry run skips it entirely. This job creates fulfillments — it emails customers tracking
     * numbers — so "writes nothing to Shopify" has to mean the whole scheduler, or a dry run is not
     * a safe way to try the automation on a live store.
     */
    @Scheduled(cron = "${sync.schedule.order-cron}")
    public void refreshOrders() {
        if (!enabled()) {
            return;
        }
        if (dryRun()) {
            log.info("Scheduled order sync (dry run): skipped, it would fulfil orders since {}",
                    orderWindowStart());
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
     * Walks every imported product the supplier still sells, but pushes only what actually changed.
     *
     * <p>The supplier still has to be read per product — there is no "what changed" endpoint for
     * inventory or pricing — but products whose values match the last push cost no Shopify calls at
     * all. In steady state that is the difference between ~2,800 mutations per run and none.
     *
     * <p>Runs are summarised by outcome rather than a bare success count, because "1,278 unchanged"
     * and "1,278 pushed" are the same number of successes and completely different situations.
     */
    private void runForEach(SyncStateStore.Kind kind, boolean dryRun) {
        if (!running.add(kind.value())) {
            log.info("A {} pass is already running; this one is skipped", kind.value());
            return;
        }
        try {
            walk(kind, dryRun);
        } finally {
            running.remove(kind.value());
        }
    }

    private void walk(SyncStateStore.Kind kind, boolean dryRun) {
        String label = kind.value() + (dryRun ? " (dry run)" : "");
        List<String> productIds = stillSold(sync.listImportedProductIds(), label);
        log.info("Scheduled {} refresh over {} imported products", label, productIds.size());

        Map<ShopifySyncService.Outcome, Integer> tally = new EnumMap<>(ShopifySyncService.Outcome.class);
        Instant startedAt = Instant.now();
        int variantsWritten = 0;
        publish(kind.value(), true, startedAt, productIds.size(), 0, tally);
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
            if (kind == SyncStateStore.Kind.PRICE && result.outcome() == ShopifySyncService.Outcome.PUSHED) {
                republishDiscounts(productId);
            }
        }

        log.info("Scheduled {} refresh complete in {}s: {} ({} variants written)",
                label, Duration.between(startedAt, Instant.now()).toSeconds(), tally, variantsWritten);
        publish(kind.value(), false, startedAt, productIds.size(), variantsWritten, tally);
        recordRun(kind.value(), startedAt, productIds.size(), tally);
    }

    /**
     * Drops the imported ids the supplier has stopped selling.
     *
     * <p>Without it the bulk of every pass is spent on ids that cannot be answered: the store's
     * tagged products cover ~2,300 supplier ids and PaceSetter still serves ~700 of them (measured
     * 2026-09-09), so two thirds of each run was a supplier call whose only outcome was a failure.
     * The sellable list is the one the console already pays for, cached for {@code sync.catalog.list-ttl}.
     *
     * <p>An unreadable or empty list keeps <em>every</em> id: skipping the whole catalog because the
     * supplier had a bad minute would stop the sync silently, which is far worse than a wasteful run.
     */
    private List<String> stillSold(List<String> productIds, String label) {
        Set<String> sellable;
        try {
            sellable = catalog.sellableProductIds();
        } catch (RuntimeException e) {
            log.warn("Could not read the sellable catalog; {} refresh keeps every imported id: {}",
                    label, e.getMessage());
            return productIds;
        }
        if (sellable.isEmpty()) {
            log.warn("The sellable catalog came back empty; {} refresh keeps every imported id", label);
            return productIds;
        }
        List<String> kept = new ArrayList<>();
        for (String productId : productIds) {
            if (sellable.contains(productId.toUpperCase(Locale.ROOT))) {
                kept.add(productId);
            }
        }
        int dropped = productIds.size() - kept.size();
        if (dropped > 0) {
            log.info("Skipping {} imported ids the supplier no longer sells ({} of {} left)",
                    dropped, kept.size(), productIds.size());
        }
        return kept;
    }

    /**
     * Republishes the quantity-break ladder of a product whose price just moved.
     *
     * <p>The variant's own price is the ladder's base, so a pushed price leaves the stored JSON
     * describing discounts off a price that no longer exists. Only the products that actually pushed
     * pay for this, and a discount failure never fails the pass — the price is already correct.
     */
    private void republishDiscounts(String productId) {
        try {
            DiscountResult result = discounts.sync(productId);
            if (result.outcome() == DiscountSyncService.Outcome.NOT_WRITTEN) {
                log.warn("Quantity discounts not republished for {}: {}", productId, result.reason());
            }
        } catch (RuntimeException e) {
            log.warn("Could not republish quantity discounts for {}: {}", productId, e.getMessage());
        }
    }

    /**
     * Publishes what a pass is doing, twice: when it starts (so a long pass is visible while it
     * runs, which no log line and no database row can show) and when it finishes.
     */
    private void publish(String job, boolean running, Instant startedAt, int products,
                         int variantsWritten, Map<ShopifySyncService.Outcome, Integer> tally) {
        Map<String, Integer> outcomes = new LinkedHashMap<>();
        tally.forEach((outcome, count) -> outcomes.put(outcome.name(), count));
        lastPasses.put(job, new JobStatus(job, running, startedAt,
                Duration.between(startedAt, Instant.now()).toSeconds(), products, variantsWritten,
                outcomes));
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
