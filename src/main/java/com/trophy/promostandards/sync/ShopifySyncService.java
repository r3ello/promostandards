package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates supplier → Shopify sync: imports/updates products ({@code productSet}), pushes
 * inventory ({@code inventorySetQuantities}), and pushes prices ({@code productVariantsBulkUpdate}).
 *
 * <p>Identity is the deterministic handle from {@link ShopifyProductMapper#handle}: a lookup that
 * returns {@code null} means create, otherwise the returned product id drives an update — so re-sync
 * is idempotent and never duplicates products.
 */
@Service
public class ShopifySyncService {

    private static final Logger log = LoggerFactory.getLogger(ShopifySyncService.class);

    /** Product metafield ({@code metaobject_reference}) pointing at the supplier's metaobject entry. */
    static final String MF_SUPPLIER_METAOBJECT = "promo_standard_supplier";

    private final ShopifyGraphQLClient gql;
    private final CatalogService catalog;
    private final ShopifyProductMapper mapper;
    private final PricingPolicy pricingPolicy;
    private final ShopifyProperties shopify;
    private final SyncProperties props;

    /** GID of the configured supplier metaobject; resolved once (it never changes for a store). */
    private volatile String supplierMetaobjectGid;

    public ShopifySyncService(ShopifyGraphQLClient gql, CatalogService catalog, ShopifyProductMapper mapper,
                              PricingPolicy pricingPolicy, ShopifyProperties shopify, SyncProperties props) {
        this.gql = gql;
        this.catalog = catalog;
        this.mapper = mapper;
        this.pricingPolicy = pricingPolicy;
        this.shopify = shopify;
        this.props = props;
    }

    /** Result of a sync operation. */
    public record SyncResult(String productId, String shopifyProductId, String handle,
                             boolean updated, int variantCount, int inventoryUpdated) {
    }

    private record VariantRef(String variantId, String inventoryItemId) {
    }

    /** Create or update the Shopify product for {@code productId}, then push inventory. */
    public SyncResult importProduct(String productId) {
        return importProduct(productId, List.of());
    }

    /**
     * Create or update the Shopify product for {@code productId}, additionally stamping the
     * user-picked {@code extraMetafields}, then push inventory.
     */
    public SyncResult importProduct(String productId, List<SyncProperties.Metafield> extraMetafields) {
        SupplierProduct product = catalog.aggregate(productId);
        JsonNode existing = findByHandle(mapper.handle(productId));
        String existingGid = existing == null ? null : existing.path("id").asText(null);

        JsonNode data = gql.execute(ShopifyGraphQL.PRODUCT_SET,
                mapper.productSetVariables(product, existingGid, withSupplierMetafield(extraMetafields)));
        JsonNode result = require(data).path("productSet");
        checkUserErrors(result, "productSet");

        JsonNode productNode = result.path("product");
        String gid = productNode.path("id").asText();
        Map<String, VariantRef> bySku = parseVariants(productNode.path("variants").path("nodes"));

        // Inventory is set within productSet (per-variant inventoryQuantities), so no second call here.
        int inventoryUpdated = inventoryPushedCount(product);
        log.info("Imported product {} -> {} ({} variants, {} inventory rows){}",
                productId, gid, bySku.size(), inventoryUpdated, existingGid != null ? " [updated]" : "");
        return new SyncResult(productId, gid, productNode.path("handle").asText(),
                existingGid != null, bySku.size(), inventoryUpdated);
    }

    /**
     * Appends the {@code custom.promo_standard_supplier} metaobject_reference metafield to the
     * per-import metafields when a supplier metaobject is configured and resolvable. Appended last
     * so it wins the mapper's namespace|key dedup over any config/UI entry with the same key.
     */
    private List<SyncProperties.Metafield> withSupplierMetafield(List<SyncProperties.Metafield> extra) {
        String metaobjectGid = supplierMetaobjectGid();
        if (metaobjectGid == null) {
            return extra;
        }
        List<SyncProperties.Metafield> all = new ArrayList<>(extra == null ? List.of() : extra);
        all.add(new SyncProperties.Metafield("custom", MF_SUPPLIER_METAOBJECT, "metaobject_reference",
                metaobjectGid, null));
        return all;
    }

    /**
     * @return the GID of the configured supplier metaobject entry ({@code sync.supplier-metaobject}),
     * or {@code null} when unconfigured or not found in the store (logged; the import proceeds
     * without the metafield). A successful lookup is cached for the life of the service.
     */
    private String supplierMetaobjectGid() {
        SyncProperties.SupplierMetaobject cfg = props.supplierMetaobject();
        if (cfg == null || cfg.handle() == null || cfg.handle().isBlank()) {
            return null;
        }
        String gid = supplierMetaobjectGid;
        if (gid != null) {
            return gid;
        }
        String type = cfg.type() == null || cfg.type().isBlank() ? MF_SUPPLIER_METAOBJECT : cfg.type();
        JsonNode data = gql.execute(ShopifyGraphQL.METAOBJECT_BY_HANDLE,
                Map.of("handle", Map.of("type", type, "handle", cfg.handle())));
        gid = require(data).path("metaobjectByHandle").path("id").asText(null);
        if (gid == null || gid.isBlank()) {
            log.warn("Supplier metaobject {}/{} not found in Shopify (needs read_metaobjects scope and "
                    + "an existing entry); importing without the custom.{} metafield",
                    type, cfg.handle(), MF_SUPPLIER_METAOBJECT);
            return null;
        }
        supplierMetaobjectGid = gid;
        return gid;
    }

    /** How many variants carry an on-hand quantity that productSet will set (location-gated). */
    private int inventoryPushedCount(SupplierProduct product) {
        if (shopify.locationId() == null || shopify.locationId().isBlank()) {
            return 0;
        }
        return (int) product.variants().stream().filter(v -> v.onHand() != null).count();
    }

    /** Refresh on-hand quantities for an already-imported product. */
    public int syncInventory(String productId) {
        SupplierProduct product = catalog.aggregate(productId);
        return pushInventory(product, requireImported(productId));
    }

    /** Recompute and push variant prices for an already-imported product. */
    public int syncPricing(String productId) {
        SupplierProduct product = catalog.aggregate(productId);
        JsonNode existing = findByHandleOrThrow(productId);
        String gid = existing.path("id").asText();
        Map<String, VariantRef> bySku = parseVariants(existing.path("variants").path("nodes"));

        List<Map<String, Object>> updates = new ArrayList<>();
        for (Variant v : product.variants()) {
            VariantRef ref = bySku.get(v.sku());
            BigDecimal price = pricingPolicy.retailPrice(v.supplierNet(), v.listPrice());
            if (ref != null && price != null) {
                updates.add(Map.of("id", ref.variantId(), "price", price.toPlainString()));
            }
        }
        if (updates.isEmpty()) {
            return 0;
        }
        JsonNode data = gql.execute(ShopifyGraphQL.VARIANTS_BULK_UPDATE,
                Map.of("productId", gid, "variants", updates));
        checkUserErrors(require(data).path("productVariantsBulkUpdate"), "productVariantsBulkUpdate");
        log.info("Synced prices for {}: {} variants", productId, updates.size());
        return updates.size();
    }

    /** @return supplier product ids of every product this app has imported (paged via Shopify). */
    public List<String> listImportedProductIds() {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> vars = cursor == null ? Map.of() : Map.of("cursor", cursor);
            JsonNode products = require(gql.execute(ShopifyGraphQL.IMPORTED_PRODUCTS, vars)).path("products");
            for (JsonNode node : products.path("nodes")) {
                String psId = node.path("metafield").path("value").asText(null);
                if (psId != null && !psId.isBlank()) {
                    ids.add(psId);
                }
            }
            JsonNode pageInfo = products.path("pageInfo");
            cursor = pageInfo.path("hasNextPage").asBoolean(false)
                    ? pageInfo.path("endCursor").asText(null) : null;
        } while (cursor != null);
        return ids;
    }

    /** @return the existing product node by handle (exact match), or {@code null} if not found. */
    JsonNode findByHandle(String handle) {
        JsonNode data = gql.execute(ShopifyGraphQL.PRODUCT_BY_HANDLE, Map.of("query", "handle:" + handle));
        JsonNode nodes = require(data).path("products").path("nodes");
        if (nodes.isArray()) {
            for (JsonNode node : nodes) {
                if (handle.equals(node.path("handle").asText(null))) {
                    return node;
                }
            }
        }
        return null;
    }

    private JsonNode findByHandleOrThrow(String productId) {
        JsonNode existing = findByHandle(mapper.handle(productId));
        if (existing == null) {
            throw new ShopifySyncException("product " + productId + " has not been imported yet");
        }
        return existing;
    }

    private Map<String, VariantRef> requireImported(String productId) {
        return parseVariants(findByHandleOrThrow(productId).path("variants").path("nodes"));
    }

    private int pushInventory(SupplierProduct product, Map<String, VariantRef> bySku) {
        if (shopify.locationId() == null || shopify.locationId().isBlank()) {
            log.warn("shopify.location-id not set; skipping inventory for {}", product.productId());
            return 0;
        }
        List<Map<String, Object>> quantities = new ArrayList<>();
        for (Variant v : product.variants()) {
            VariantRef ref = bySku.get(v.sku());
            if (v.onHand() == null || ref == null || ref.inventoryItemId() == null) {
                continue;
            }
            quantities.add(Map.of(
                    "inventoryItemId", ref.inventoryItemId(),
                    "locationId", shopify.locationId(),
                    "quantity", v.onHand()));
        }
        if (quantities.isEmpty()) {
            return 0;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "available");
        input.put("reason", "correction");
        input.put("ignoreCompareQuantity", true);
        input.put("quantities", quantities);

        JsonNode data = gql.execute(ShopifyGraphQL.INVENTORY_SET_QUANTITIES, Map.of("input", input));
        checkUserErrors(require(data).path("inventorySetQuantities"), "inventorySetQuantities");
        return quantities.size();
    }

    private Map<String, VariantRef> parseVariants(JsonNode nodes) {
        Map<String, VariantRef> bySku = new LinkedHashMap<>();
        if (nodes != null && nodes.isArray()) {
            for (JsonNode n : nodes) {
                String sku = n.path("sku").asText(null);
                if (sku != null) {
                    String invItem = n.path("inventoryItem").path("id").asText(null);
                    bySku.put(sku, new VariantRef(n.path("id").asText(null), invItem));
                }
            }
        }
        return bySku;
    }

    private static JsonNode require(JsonNode data) {
        if (data == null) {
            throw new ShopifySyncException("Shopify returned no data");
        }
        return data;
    }

    private static void checkUserErrors(JsonNode result, String op) {
        JsonNode userErrors = result.path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            throw new ShopifySyncException(op + " userErrors: " + userErrors);
        }
    }
}
