package com.trophy.promostandards.sync.web;

import com.trophy.promostandards.db.SyncStateStore;
import com.trophy.promostandards.discount.DiscountSyncService;
import com.trophy.promostandards.discount.DiscountSyncService.DiscountResult;
import com.trophy.promostandards.sync.MetafieldCatalogService;
import com.trophy.promostandards.sync.OrderSyncService;
import com.trophy.promostandards.sync.OrderSyncService.OrderSyncResult;
import com.trophy.promostandards.sync.ShopifySyncService;
import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.SyncProperties;
import com.trophy.promostandards.sync.SyncScheduler;
import com.trophy.promostandards.sync.model.MetafieldDefinitionView;
import com.trophy.promostandards.sync.model.MetafieldSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * REST facade to trigger supplier → Shopify sync on demand. Scheduled jobs call the same
 * {@link ShopifySyncService}; these endpoints expose the manual path.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final ShopifySyncService sync;
    private final OrderSyncService orderSync;
    private final MetafieldCatalogService metafieldCatalog;
    private final DiscountSyncService discounts;
    private final SyncScheduler scheduler;

    public SyncController(ShopifySyncService sync, OrderSyncService orderSync,
                          MetafieldCatalogService metafieldCatalog, DiscountSyncService discounts,
                          SyncScheduler scheduler) {
        this.sync = sync;
        this.orderSync = orderSync;
        this.metafieldCatalog = metafieldCatalog;
        this.discounts = discounts;
        this.scheduler = scheduler;
    }

    /**
     * Whether the unattended refresh is on, and what its last pass did.
     *
     * <p>The one way to answer "is this actually running?" without a terminal: on the server the log
     * lives inside the container, and a pass that is still walking the catalog has written neither a
     * log summary nor a {@code sync_run} row yet.
     */
    @GetMapping("/schedule")
    public SyncScheduler.ScheduleStatus schedule() {
        return scheduler.status();
    }

    /**
     * Starts one refresh pass now, in the background, without waiting for its cron — and without
     * needing the cron jobs switched on at all.
     *
     * <p>How the automation is proven before it is trusted: {@code ?dryRun=true} walks the whole
     * catalog, writes nothing to Shopify, and leaves in {@code GET /schedule} both what it would
     * have pushed and how long it took. The response returns immediately; a pass outlives any
     * request.
     *
     * @param kind {@code inventory} or {@code price}
     */
    @PostMapping("/schedule/{kind}")
    public SyncScheduler.ScheduleStatus runPass(@PathVariable String kind,
                                                @RequestParam(defaultValue = "false") boolean dryRun) {
        return scheduler.runNow(kindOf(kind), dryRun);
    }

    private static SyncStateStore.Kind kindOf(String kind) {
        for (SyncStateStore.Kind candidate : SyncStateStore.Kind.values()) {
            if (candidate.value().equalsIgnoreCase(kind) || candidate.name().equalsIgnoreCase(kind)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("Unknown refresh kind '" + kind + "': expected inventory or price");
    }

    /**
     * What the console needs to know before offering an action: {@code createProducts} false means
     * a supplier id no store product carries cannot be imported, so there is no "Add to Shopify".
     */
    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("createProducts", sync.canCreateProducts());
    }

    /** List the store's existing product metafield definitions so the UI can offer them at import. */
    @GetMapping("/metafield-definitions")
    public List<MetafieldDefinitionView> metafieldDefinitions() {
        return metafieldCatalog.listProductDefinitions();
    }

    /** Sample example values for one metafield (so the user can copy an existing product's value). */
    @GetMapping("/metafield-definitions/{namespace}/{key}/examples")
    public List<MetafieldSample> metafieldExamples(@PathVariable String namespace,
                                                   @PathVariable String key,
                                                   @RequestParam(defaultValue = "5") int limit) {
        return metafieldCatalog.sampleValues(namespace, key, limit);
    }

    /**
     * Import (create or update) a single supplier product, then publish its quantity discounts. The
     * optional body carries the metafields the user picked in the UI; with no body the product
     * imports with just its identity metafields.
     *
     * <p>The two belong together — a product whose price ladder is not published is priced wrong for
     * every quantity above the first break — so this endpoint does both rather than leaving the
     * second call to whoever remembers. Discounts never fail the import: a failure there is reported
     * in {@code discountError} and the product is still imported.
     */
    @PostMapping("/products/{productId}")
    public ImportResponse importProduct(@PathVariable String productId,
                                        @RequestBody(required = false) ImportRequest body) {
        List<SyncProperties.Metafield> metafields =
                body == null || body.metafields() == null ? List.of() : body.metafields();
        return withDiscounts(sync.importProduct(productId, metafields));
    }

    /** Import a batch of supplier products; per-product failures are reported, not fatal. */
    @PostMapping("/products")
    public List<BatchItem> importProducts(@RequestBody List<String> productIds) {
        List<BatchItem> results = new ArrayList<>();
        for (String productId : productIds) {
            try {
                results.add(BatchItem.ok(withDiscounts(sync.importProduct(productId))));
            } catch (RuntimeException e) {
                results.add(BatchItem.failed(productId, e.getMessage()));
            }
        }
        return results;
    }

    /**
     * Publishes the product's quantity-break ladder and reports it alongside the import. A failure
     * here is recorded, not thrown: the product is already in Shopify, and losing that result to a
     * failed metafield write would tell the caller the import failed when it did not.
     */
    private ImportResponse withDiscounts(SyncResult result) {
        try {
            return ImportResponse.of(result, discounts.sync(result.productId()));
        } catch (RuntimeException e) {
            log.warn("Imported {} but could not publish its discounts: {}", result.productId(),
                    e.getMessage());
            return ImportResponse.of(result, null, e.getMessage());
        }
    }

    /** Refresh inventory for an already-imported product. */
    @PostMapping("/products/{productId}/inventory")
    public Map<String, Object> syncInventory(@PathVariable String productId) {
        return Map.of("productId", productId, "inventoryUpdated", sync.syncInventory(productId));
    }

    /** Refresh prices for an already-imported product. */
    @PostMapping("/products/{productId}/pricing")
    public Map<String, Object> syncPricing(@PathVariable String productId) {
        return Map.of("productId", productId, "pricesUpdated", sync.syncPricing(productId));
    }

    /**
     * Push supplier order status + shipment tracking onto Shopify orders. {@code since} defaults to
     * 24h ago when omitted.
     */
    @PostMapping("/orders")
    public OrderSyncResult syncOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        return orderSync.syncOrders(since != null ? since : Instant.now().minus(1, ChronoUnit.DAYS));
    }

    /** Optional body for a single-product import: the metafields the user picked in the UI. */
    public record ImportRequest(List<SyncProperties.Metafield> metafields) {
    }

    /** One entry in a batch import response. */
    /**
     * What an import did, in one object: the product side flattened so existing callers keep reading
     * the same fields, plus what happened to its discounts.
     */
    public record ImportResponse(String productId, String shopifyProductId, String handle,
                                 boolean updated, int variantCount, int inventoryUpdated,
                                 List<String> warnings, DiscountResult discounts,
                                 String discountError) {

        static ImportResponse of(SyncResult r, DiscountResult discounts) {
            return of(r, discounts, null);
        }

        static ImportResponse of(SyncResult r, DiscountResult discounts, String discountError) {
            return new ImportResponse(r.productId(), r.shopifyProductId(), r.handle(), r.updated(),
                    r.variantCount(), r.inventoryUpdated(), r.warnings(), discounts, discountError);
        }
    }

    public record BatchItem(String productId, boolean success, ImportResponse result, String error) {
        static BatchItem ok(ImportResponse r) {
            return new BatchItem(r.productId(), true, r, null);
        }

        static BatchItem failed(String productId, String error) {
            return new BatchItem(productId, false, null, error);
        }
    }
}
