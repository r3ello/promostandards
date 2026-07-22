package com.trophy.promostandards.sync.web;

import com.trophy.promostandards.sync.MetafieldCatalogService;
import com.trophy.promostandards.sync.OrderSyncService;
import com.trophy.promostandards.sync.OrderSyncService.OrderSyncResult;
import com.trophy.promostandards.sync.ShopifySyncService;
import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.SyncProperties;
import com.trophy.promostandards.sync.model.MetafieldDefinitionView;
import com.trophy.promostandards.sync.model.MetafieldSample;
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

    private final ShopifySyncService sync;
    private final OrderSyncService orderSync;
    private final MetafieldCatalogService metafieldCatalog;

    public SyncController(ShopifySyncService sync, OrderSyncService orderSync,
                          MetafieldCatalogService metafieldCatalog) {
        this.sync = sync;
        this.orderSync = orderSync;
        this.metafieldCatalog = metafieldCatalog;
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
     * Import (create or update) a single supplier product. The optional body carries the metafields
     * the user picked in the UI; with no body the product imports with just its identity metafields.
     */
    @PostMapping("/products/{productId}")
    public SyncResult importProduct(@PathVariable String productId,
                                    @RequestBody(required = false) ImportRequest body) {
        List<SyncProperties.Metafield> metafields =
                body == null || body.metafields() == null ? List.of() : body.metafields();
        return sync.importProduct(productId, metafields);
    }

    /** Import a batch of supplier products; per-product failures are reported, not fatal. */
    @PostMapping("/products")
    public List<BatchItem> importProducts(@RequestBody List<String> productIds) {
        List<BatchItem> results = new ArrayList<>();
        for (String productId : productIds) {
            try {
                results.add(BatchItem.ok(sync.importProduct(productId)));
            } catch (RuntimeException e) {
                results.add(BatchItem.failed(productId, e.getMessage()));
            }
        }
        return results;
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
    public record BatchItem(String productId, boolean success, SyncResult result, String error) {
        static BatchItem ok(SyncResult r) {
            return new BatchItem(r.productId(), true, r, null);
        }

        static BatchItem failed(String productId, String error) {
            return new BatchItem(productId, false, null, error);
        }
    }
}
