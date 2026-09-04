package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.db.SyncStateStore;
import com.trophy.promostandards.db.SyncStateStore.Kind;
import com.trophy.promostandards.discount.DiscountProperties;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates supplier → Shopify sync: imports/updates products ({@code productSet}), pushes
 * inventory ({@code inventorySetQuantities}), and pushes prices ({@code productVariantsBulkUpdate}).
 *
 * <p>Identity is resolved in two steps. Products this app created carry the deterministic handle
 * from {@link ShopifyProductMapper#handle} — found means update, missing means create. Products the
 * one-shot trophypartner migration created have their own handles but share the metafield contract
 * ({@code custom.ps_product_ids} lists every supplier id they cover, canonical first), so a handle
 * miss falls back to a cached index over those lists before ever creating — re-sync is idempotent
 * and never duplicates a migrated product.
 *
 * <p><b>Migrated products are never {@code productSet}:</b> that mutation is declarative — variants
 * not listed would be <em>deleted</em> (fatal for N:1 grouped products whose other supplier ids own
 * the sibling variants) and hand-migrated content would be replaced. They are updated per-variant
 * only (inventory + price, matched by SKU), per the P1 adoption plan.
 */
@Service
public class ShopifySyncService {

    private static final Logger log = LoggerFactory.getLogger(ShopifySyncService.class);

    /** Product metafield ({@code metaobject_reference}) pointing at the supplier's metaobject entry. */
    static final String MF_SUPPLIER_METAOBJECT = "promo_standard_supplier";

    /** How long the supplier-id → store-product index is trusted before repaging Shopify. */
    private static final Duration INDEX_TTL = Duration.ofMinutes(5);


    private final ShopifyGraphQLClient gql;
    private final CatalogService catalog;
    private final ShopifyProductMapper mapper;
    private final PricingPolicy pricingPolicy;
    private final ShopifyProperties shopify;
    private final SyncProperties props;
    private final ObjectMapper objectMapper;
    /** Absent unless persistence is on; absent means "push everything", i.e. the pre-database behaviour. */
    private final ObjectProvider<SyncStateStore> syncStates;
    /** Only for the metafield the quantity ladder is published in: the store index reads it per row. */
    private final DiscountProperties discounts;

    /** Variant-level sync for store products this app did not create (migrated). */
    private final ForeignProductSync foreignSync;

    /** GID of the configured supplier metaobject; resolved once (it never changes for a store). */
    private volatile String supplierMetaobjectGid;

    /** UPPER-cased supplier id → tagged store product; rebuilt lazily after {@link #INDEX_TTL}. */
    private volatile Map<String, ImportedProduct> supplierIdIndex;
    private volatile Instant indexBuiltAt;

    /** Serialises index rebuilds: a burst of catalog rows costs one Shopify pass, not one each. */
    private final Object indexLock = new Object();

    /** Striped per-product locks so a manual sync and a scheduled pass never overlap on one product. */
    private final Object[] productLocks = new Object[32];

    {
        for (int i = 0; i < productLocks.length; i++) {
            productLocks[i] = new Object();
        }
    }

    public ShopifySyncService(ShopifyGraphQLClient gql, CatalogService catalog, ShopifyProductMapper mapper,
                              PricingPolicy pricingPolicy, ShopifyProperties shopify, SyncProperties props,
                              ObjectMapper objectMapper, ObjectProvider<SyncStateStore> syncStates,
                              DiscountProperties discounts) {
        this.discounts = discounts;
        this.gql = gql;
        this.catalog = catalog;
        this.mapper = mapper;
        this.pricingPolicy = pricingPolicy;
        this.shopify = shopify;
        this.props = props;
        this.objectMapper = objectMapper;
        this.syncStates = syncStates;
        this.foreignSync = new ForeignProductSync(gql, catalog, pricingPolicy, shopify, props, objectMapper);
    }

    /**
     * Result of a sync operation.
     *
     * @param warnings what the supplier could not answer for, carried up from
     *                 {@link CatalogService#aggregate}: an import that went through without stock or
     *                 without images has to say so, or it reads as a complete sync
     */
    public record SyncResult(String productId, String shopifyProductId, String handle,
                             boolean updated, int variantCount, int inventoryUpdated,
                             List<String> warnings) {
    }

    /**
     * A store variant as the app needs it for a push. {@code available} is the quantity Shopify
     * currently holds at the configured location — {@code null} when the item is not stocked there
     * — and is required by {@code inventorySetQuantities} as the compare-and-set baseline.
     */
    private record VariantRef(String variantId, String inventoryItemId, Integer available) {
    }

    /**
     * One PromoStandards-tagged store product, as listed by {@code IMPORTED_PRODUCTS}.
     *
     * @param discountsJson the published quantity-break ladder, or null — read here rather than per
     *                      row, because this index is the catalog's only store listing
     */
    record ImportedProduct(String gid, String handle, String canonicalId, List<String> supplierIds,
                           String source, String discountsJson) {
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
        if (existing == null) {
            // Creating is the irreversible half: a wrong "not in the store" duplicates the product,
            // so this lookup is allowed to pay for a fresh index.
            JsonNode foreign = findForeign(productId, true);
            if (foreign != null) {
                return updateForeignInPlace(productId, product, foreign);
            }
        }
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
        stampSyncMetafields(gid, true);
        // A product that did not exist a moment ago now does: drop the cached index so the catalog's
        // "imported" badge reflects it on the next read instead of waiting out the TTL.
        indexBuiltAt = null;
        log.info("Imported product {} -> {} ({} variants, {} inventory rows){}",
                productId, gid, bySku.size(), inventoryUpdated, existingGid != null ? " [updated]" : "");
        return new SyncResult(productId, gid, productNode.path("handle").asText(),
                existingGid != null, bySku.size(), inventoryUpdated, product.warnings());
    }

    /**
     * Variant-level sync for a product this app did not create (found via the {@code ps_product_ids}
     * index, typically migrated). Never {@code productSet}: that is declarative and would delete the
     * variants of the other supplier ids a grouped product covers, along with the migrated title,
     * body and images. {@link ForeignProductSync} adopts the product's legacy variant, creates the
     * supplier variants it is missing, and pushes price + stock. {@code ps_source} is never written
     * here (it is the immutable provenance flag), only {@code ps_last_sync_at}.
     */
    private SyncResult updateForeignInPlace(String productId, SupplierProduct product, JsonNode node) {
        String gid = node.path("id").asText();
        String handle = node.path("handle").asText();
        ForeignProductSync.Result result =
                foreignSync.sync(productId, product, node, indexLookup(productId, false));
        stampSyncMetafields(gid, false);
        if (!result.supplierIdsAdded().isEmpty()) {
            // The product now covers ids the cached index has never seen: let the badge catch up.
            indexBuiltAt = null;
        }
        return new SyncResult(productId, gid, handle, true, result.variants(), result.inventoryUpdated(),
                product.warnings());
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

    /** Refresh on-hand quantities for an already-imported (or migrated) product. */
    public int syncInventory(String productId) {
        return refresh(productId, Kind.INVENTORY, false, true).updated();
    }

    /** Recompute and push variant prices for an already-imported (or migrated) product. */
    public int syncPricing(String productId) {
        int updated = refresh(productId, Kind.PRICE, false, true).updated();
        log.info("Synced prices for {}: {} variants", productId, updated);
        return updated;
    }

    /** What a refresh did, or why it did nothing. */
    public enum Outcome {
        /** Values differed and Shopify accepted the push. */
        PUSHED,
        /** The supplier's values match what was last pushed — nothing to do. */
        UNCHANGED,
        /** Would have pushed, but this was a dry run. */
        WOULD_PUSH,
        /** Previous attempts failed; still inside the retry backoff window. */
        BACKING_OFF,
        /** The push was attempted and failed; the product stays due. */
        FAILED,
        /** Sync bookkeeping is unreachable, so pushing would be flying blind. Skipped on purpose. */
        STATE_UNAVAILABLE
    }

    /** @param updated variants actually written; zero for every outcome other than PUSHED. */
    public record RefreshResult(String productId, Kind kind, Outcome outcome, int updated, String error) {
    }

    /**
     * Pushes a product's inventory or prices <b>only when they differ from what was last pushed</b>.
     *
     * <p>This is what makes scheduled syncing affordable. The comparison is against the stored digest
     * of the previous push, not against Shopify, so an unchanged product costs its supplier read and
     * <em>zero</em> Shopify calls — where the old behaviour spent two or three mutations per product
     * per run, on ~943 products, every 30 minutes.
     *
     * <p>Ordering matters for correctness: the digest is written only after Shopify accepts the
     * mutation. Writing it earlier would mark a failed push as done and the product would never
     * retry.
     *
     * @param force skip the digest check and push regardless — what a person clicking "Sync" means
     */
    public RefreshResult refresh(String productId, Kind kind, boolean dryRun, boolean force) {
        // One instance runs the scheduler, so an in-process lock is enough to stop a manual sync and
        // a cron pass from pushing the same product at once. A second instance would need a lease in
        // the database (see POSTGRES-PLAN.md).
        synchronized (lockFor(productId)) {
            SyncStateStore store = syncStates.getIfAvailable();
            SyncStateStore.State state = null;
            if (store != null) {
                try {
                    state = store.find(productId, kind).orElse(null);
                } catch (RuntimeException e) {
                    // Never treat "cannot read state" as "nothing was ever pushed": that would turn a
                    // database blip into a full-catalog write storm.
                    log.warn("Sync state unavailable for {} ({}): {}", productId, kind, e.getMessage());
                    return new RefreshResult(productId, kind, Outcome.STATE_UNAVAILABLE, 0, e.getMessage());
                }
            }
            if (!force && state != null && state.isBackingOff(Instant.now())) {
                return new RefreshResult(productId, kind, Outcome.BACKING_OFF, 0, null);
            }

            SupplierProduct product = catalog.aggregate(productId);
            String digest = kind == Kind.PRICE
                    ? SyncDigest.forPrices(product, pricingPolicy, props.currency())
                    : SyncDigest.forInventory(product, shopify.locationId());

            if (!force && state != null && digest.equals(state.payloadHash())) {
                return new RefreshResult(productId, kind, Outcome.UNCHANGED, 0, null);
            }
            if (dryRun) {
                return new RefreshResult(productId, kind, Outcome.WOULD_PUSH, 0, null);
            }

            try {
                int updated = push(productId, product, kind);
                if (store != null) {
                    store.recordSuccess(productId, kind, digest);
                }
                return new RefreshResult(productId, kind, Outcome.PUSHED, updated, null);
            } catch (RuntimeException e) {
                if (store != null) {
                    store.recordFailure(productId, kind, e.getMessage());
                }
                if (force) {
                    throw e;    // a person asked for this one; surface the failure to them
                }
                return new RefreshResult(productId, kind, Outcome.FAILED, 0, e.getMessage());
            }
        }
    }

    /** Resolves the store product once and writes whichever side this refresh is responsible for. */
    private int push(String productId, SupplierProduct product, Kind kind) {
        JsonNode existing = resolveOrThrow(productId);
        String gid = existing.path("id").asText();
        Map<String, VariantRef> bySku = parseVariants(existing.path("variants").path("nodes"));
        int updated = kind == Kind.PRICE
                ? pushPrices(product, gid, bySku)
                : pushInventory(product, bySku);
        stampSyncMetafields(gid, false);
        return updated;
    }

    /** Fixed set of locks striped by product id — bounded, unlike a lock per product id. */
    private Object lockFor(String productId) {
        int index = Math.floorMod(productId.toUpperCase(Locale.ROOT).hashCode(), productLocks.length);
        return productLocks[index];
    }

    private int pushPrices(SupplierProduct product, String gid, Map<String, VariantRef> bySku) {
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
        return updates.size();
    }

    /**
     * @return every supplier product id covered by a PromoStandards-tagged store product: the
     * canonical {@code ps_product_id} plus every member of {@code ps_product_ids} (migrated N:1
     * products cover several). Backs the catalog "imported" badge and the scheduled refreshes.
     */
    public List<String> listImportedProductIds() {
        Map<String, String> byKey = new LinkedHashMap<>();
        for (ImportedProduct p : listImportedProducts()) {
            for (String id : p.supplierIds()) {
                byKey.putIfAbsent(id.toUpperCase(Locale.ROOT), id);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    /**
     * Cheap "does the store already cover this supplier id?" for the catalog table: answered from
     * the cached supplier-id index, which repages Shopify at most once per {@link #INDEX_TTL} (and
     * once for the whole burst, not once per caller). The catalog list refreshes the index up front,
     * so the per-row detail calls behind it normally cost <b>zero</b> Shopify requests.
     *
     * @return whether the id is imported, or {@code null} when Shopify isn't configured and no index
     * could be built (the console renders that as "—", i.e. unknown).
     */
    public Boolean isImported(String productId) {
        Map<String, ImportedProduct> index = cachedIndexOrNull();
        return index == null ? null : index.containsKey(productId.toUpperCase(Locale.ROOT));
    }

    /**
     * @return whether this supplier id's store product carries a published quantity-break ladder
     * (false when it has none, or when Shopify is not connected). Answered from the cached
     * supplier-id index — the catalog asks this once per row, so it must not cost a request.
     */
    public boolean hasDiscounts(String productId) {
        Map<String, ImportedProduct> index = cachedIndexOrNull();
        if (index == null) {
            return false;
        }
        ImportedProduct match = index.get(productId.toUpperCase(Locale.ROOT));
        String json = match == null ? null : match.discountsJson();
        return json != null && !json.isBlank();
    }

    /** @return the supplier-id index (refreshed if stale), or null when unconfigured/never built. */
    private Map<String, ImportedProduct> cachedIndexOrNull() {
        if (shopify.storeDomain() == null || shopify.storeDomain().isBlank()) {
            return null;
        }
        try {
            return ensureIndex(false);
        } catch (RuntimeException e) {
            log.warn("Could not list imported products from Shopify: {}", e.getMessage());
            return supplierIdIndex;  // a stale answer beats hammering a failing store per row
        }
    }

    /**
     * @return the supplier-id index, repaging Shopify only when it is missing, stale, or
     * {@code force}d. Concurrent callers share a single pass rather than each starting their own.
     */
    private Map<String, ImportedProduct> ensureIndex(boolean force) {
        if (!force && isIndexFresh()) {
            return supplierIdIndex;
        }
        synchronized (indexLock) {
            // Another thread may have rebuilt it while we waited on the lock.
            if (!force && isIndexFresh()) {
                return supplierIdIndex;
            }
            listImportedProducts();
            return supplierIdIndex;
        }
    }

    private boolean isIndexFresh() {
        Map<String, ImportedProduct> index = supplierIdIndex;
        Instant builtAt = indexBuiltAt;
        return index != null && builtAt != null && builtAt.plus(INDEX_TTL).isAfter(Instant.now());
    }

    /**
     * @return every tagged store product, or an empty list when Shopify is unconfigured or
     * unreachable — for callers where store data is an enrichment, not a requirement (e.g. the
     * catalog group index deriving families from migrated {@code ps_product_ids} lists).
     */
    List<ImportedProduct> importedProductsOrEmpty() {
        if (shopify.storeDomain() == null || shopify.storeDomain().isBlank()) {
            return List.of();
        }
        try {
            return listImportedProducts();
        } catch (RuntimeException e) {
            log.warn("Could not list imported products from Shopify: {}", e.getMessage());
            return List.of();
        }
    }

    /** Pages every tagged store product and refreshes the supplier-id index as a side effect. */
    private List<ImportedProduct> listImportedProducts() {
        List<ImportedProduct> products = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> vars = new LinkedHashMap<>();
            vars.put("discountNamespace", discounts.namespace());
            vars.put("discountKey", discounts.key());
            if (cursor != null) {
                vars.put("cursor", cursor);
            }
            JsonNode page = require(gql.execute(ShopifyGraphQL.IMPORTED_PRODUCTS, vars)).path("products");
            for (JsonNode node : page.path("nodes")) {
                String canonical = node.path("psId").path("value").asText(null);
                List<String> ids = new ArrayList<>();
                if (canonical != null && !canonical.isBlank()) {
                    ids.add(canonical);
                }
                for (String id : parseIdList(node.path("psIds").path("value").asText(null))) {
                    if (ids.stream().noneMatch(id::equalsIgnoreCase)) {
                        ids.add(id);
                    }
                }
                if (ids.isEmpty()) {
                    continue;
                }
                products.add(new ImportedProduct(node.path("id").asText(null),
                        node.path("handle").asText(null), canonical, List.copyOf(ids),
                        node.path("psSource").path("value").asText(null),
                        node.path("discounts").path("value").asText(null)));
            }
            JsonNode pageInfo = page.path("pageInfo");
            cursor = pageInfo.path("hasNextPage").asBoolean(false)
                    ? pageInfo.path("endCursor").asText(null) : null;
        } while (cursor != null);

        Map<String, ImportedProduct> index = new LinkedHashMap<>();
        for (ImportedProduct p : products) {
            for (String id : p.supplierIds()) {
                index.putIfAbsent(id.toUpperCase(Locale.ROOT), p);
            }
        }
        supplierIdIndex = index;
        indexBuiltAt = Instant.now();
        return products;
    }

    /** A list metafield value is a JSON array ({@code ["A","B"]}); tolerate a plain CSV string too. */
    private List<String> parseIdList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        if (raw.stripLeading().startsWith("[")) {
            try {
                for (JsonNode e : objectMapper.readTree(raw)) {
                    if (e.isTextual() && !e.asText().isBlank()) {
                        ids.add(e.asText().strip());
                    }
                }
                return ids;
            } catch (Exception e) {
                log.warn("Unparseable ps_product_ids value {}: {}", raw, e.getMessage());
            }
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                ids.add(part.strip());
            }
        }
        return ids;
    }

    /**
     * @return the store product (with variants) covering {@code productId} per the supplier-id
     * index — i.e. a product this app did not create under its own handle — or {@code null}.
     * A stale index hit (product deleted since the last page) forces one rebuild before giving up.
     */
    private JsonNode findForeign(String productId) {
        return findForeign(productId, false);
    }

    /**
     * @param rebuildOnMiss repage Shopify when the cached index has never heard of this id, instead
     *                      of trusting a five-minute-old "no". Only the create path asks for it, and
     *                      it matters because of how this store is actually run: products are
     *                      migrated in with Matrixify and imported from here minutes later, so a
     *                      stale index would answer "not in the store" for a product that is, and
     *                      the import would create a duplicate under the app's own handle. It costs
     *                      one pagination, and only on the path that is about to create a product —
     *                      already the most expensive thing this app does.
     */
    private JsonNode findForeign(String productId, boolean rebuildOnMiss) {
        Instant builtBefore = indexBuiltAt;
        ImportedProduct match = indexLookup(productId, false);
        // Only worth looking again when the "no" came from a cached index: if the lookup above built
        // it just now, that answer is as fresh as a second pass would be.
        if (match == null && rebuildOnMiss && builtBefore != null && builtBefore.equals(indexBuiltAt)) {
            match = indexLookup(productId, true);
        }
        if (match == null) {
            return null;
        }
        JsonNode node = findByHandle(match.handle());
        if (node == null) {
            match = indexLookup(productId, true);
            node = match == null ? null : findByHandle(match.handle());
        }
        return node;
    }


    private ImportedProduct indexLookup(String productId, boolean forceRebuild) {
        return ensureIndex(forceRebuild).get(productId.toUpperCase(Locale.ROOT));
    }

    /**
     * Stamps {@code custom.ps_last_sync_at} (every sync) and, for app-created products only,
     * {@code custom.ps_source=app}. Never called with {@code appOwned} for foreign/migrated products
     * — their {@code ps_source=migration} is immutable. Failures warn, never fail the sync.
     */
    private void stampSyncMetafields(String productGid, boolean appOwned) {
        List<Map<String, Object>> metafields = new ArrayList<>();
        metafields.add(Map.of(
                "ownerId", productGid,
                "namespace", ShopifyProductMapper.METAFIELD_NAMESPACE,
                "key", ShopifyProductMapper.MF_LAST_SYNC_AT,
                "type", "date_time",
                "value", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()));
        if (appOwned) {
            metafields.add(Map.of(
                    "ownerId", productGid,
                    "namespace", ShopifyProductMapper.METAFIELD_NAMESPACE,
                    "key", ShopifyProductMapper.MF_SOURCE,
                    "type", "single_line_text_field",
                    "value", ShopifyProductMapper.SOURCE_APP));
        }
        try {
            JsonNode data = gql.execute(ShopifyGraphQL.METAFIELDS_SET, Map.of("metafields", metafields));
            checkUserErrors(require(data).path("metafieldsSet"), "metafieldsSet");
        } catch (RuntimeException e) {
            log.warn("Could not stamp sync metafields on {}: {}", productGid, e.getMessage());
        }
    }

    /** @return the existing product node by handle (exact match), or {@code null} if not found. */
    JsonNode findByHandle(String handle) {
        JsonNode data = gql.execute(ShopifyGraphQL.PRODUCT_BY_HANDLE, productByHandleVariables(handle));
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

    /**
     * Variables for {@code PRODUCT_BY_HANDLE}. The per-location stock is only asked for when a
     * location is configured; {@code $locationId} is non-null in the schema, so an unused-but-valid
     * GID rides along with {@code @include(if:)} switched off rather than splitting the document.
     */
    private Map<String, Object> productByHandleVariables(String handle) {
        String locationId = shopify.locationId();
        boolean configured = locationId != null && !locationId.isBlank();
        return Map.of("query", "handle:" + handle,
                "locationId", configured ? locationId : "gid://shopify/Location/0",
                "withLocation", configured);
    }

    /**
     * The store product covering {@code productId} — app handle first, then the migrated-product
     * index. Public because the discount sync needs the same resolution (and the same node: its
     * variants, their prices and their {@code custom.promo_standard_id} all come from this one query).
     *
     * @throws ShopifySyncException when the product has not been imported yet
     */
    public JsonNode storeProduct(String productId) {
        return resolveOrThrow(productId);
    }

    /**
     * @return every supplier id the store product covering {@code productId} stands for — a grouped
     * product covers several ({@code ps_product_ids}) — or just {@code productId} when nothing in the
     * store claims it. Answered from the cached index, so it costs no request.
     */
    public List<String> supplierIdsFor(String productId) {
        Map<String, ImportedProduct> index = cachedIndexOrNull();
        ImportedProduct match = index == null ? null : index.get(productId.toUpperCase(Locale.ROOT));
        return match == null || match.supplierIds().isEmpty() ? List.of(productId) : match.supplierIds();
    }

    /**
     * Writes one metafield on a set of variants of a product, in a single mutation. The discount sync
     * writes each variant's quantity ladder this way — a grouped product can hold parts priced on
     * different ladders, so the value cannot live on the product alone.
     */
    public void setVariantMetafield(String productGid, List<String> variantGids, String namespace,
                                    String key, String type, String value) {
        if (variantGids == null || variantGids.isEmpty()) {
            return;
        }
        List<Map<String, Object>> variants = new ArrayList<>();
        for (String variantGid : variantGids) {
            variants.add(Map.of("id", variantGid, "metafields", List.of(Map.of(
                    "namespace", namespace, "key", key, "type", type, "value", value))));
        }
        JsonNode data = gql.execute(ShopifyGraphQL.VARIANTS_BULK_UPDATE,
                Map.of("productId", productGid, "variants", variants));
        checkUserErrors(require(data).path("productVariantsBulkUpdate"), "productVariantsBulkUpdate");
    }

    /**
     * Drops the cached supplier-id index so the next read repages Shopify. Called after writing a
     * metafield the index carries (the quantity ladder), so the catalog's badge shows the change at
     * once instead of waiting out the TTL.
     */
    public void invalidateImportedIndex() {
        indexBuiltAt = null;
    }

    /**
     * Writes one product metafield. Used by the discount sync to publish the product-wide quantity
     * ladder, which is also what the catalog badge reads off the store index.
     */
    public void setProductMetafield(String productGid, String namespace, String key, String type,
                                    String value) {
        Map<String, Object> metafield = Map.of(
                "ownerId", productGid, "namespace", namespace, "key", key, "type", type, "value", value);
        JsonNode data = gql.execute(ShopifyGraphQL.METAFIELDS_SET,
                Map.of("metafields", List.of(metafield)));
        checkUserErrors(require(data).path("metafieldsSet"), "metafieldsSet");
    }

    /** App handle first, then the migrated-product index; throws when neither knows the id. */
    private JsonNode resolveOrThrow(String productId) {
        JsonNode existing = findByHandle(mapper.handle(productId));
        if (existing == null) {
            existing = findForeign(productId);
        }
        if (existing == null) {
            throw new ShopifySyncException("product " + productId + " has not been imported yet");
        }
        return existing;
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
            if (ref.available() == null) {
                // Not stocked at this location yet: setting a quantity there would be refused.
                activateInventory(gql, ref.inventoryItemId(), shopify.locationId());
            }
            quantities.add(Map.of(
                    "inventoryItemId", ref.inventoryItemId(),
                    "locationId", shopify.locationId(),
                    "quantity", v.onHand(),
                    "changeFromQuantity", ref.available() == null ? 0 : ref.available()));
        }
        if (quantities.isEmpty()) {
            return 0;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "available");
        input.put("reason", "correction");
        input.put("quantities", quantities);

        // A fresh key per push: the client's throttling retry resends the same body, and Shopify
        // must count that as the one write it is.
        Map<String, Object> vars = Map.of("input", input,
                "idempotencyKey", UUID.randomUUID().toString());
        JsonNode data = gql.execute(ShopifyGraphQL.INVENTORY_SET_QUANTITIES, vars);
        checkUserErrors(require(data).path("inventorySetQuantities"), "inventorySetQuantities");
        return quantities.size();
    }

    private Map<String, VariantRef> parseVariants(JsonNode nodes) {
        Map<String, VariantRef> bySku = new LinkedHashMap<>();
        if (nodes != null && nodes.isArray()) {
            for (JsonNode n : nodes) {
                String sku = n.path("sku").asText(null);
                if (sku != null) {
                    JsonNode item = n.path("inventoryItem");
                    bySku.put(sku, new VariantRef(n.path("id").asText(null),
                            item.path("id").asText(null), availableAt(item)));
                }
            }
        }
        return bySku;
    }

    /** @return the "available" quantity the store holds at the configured location, or null. */
    static Integer availableAt(JsonNode inventoryItem) {
        JsonNode level = inventoryItem.path("inventoryLevel");
        if (level.isMissingNode() || level.isNull()) {
            return null;
        }
        for (JsonNode quantity : level.path("quantities")) {
            if ("available".equals(quantity.path("name").asText())) {
                return quantity.path("quantity").asInt();
            }
        }
        return null;
    }

    /**
     * Stocks an inventory item at a location so its quantity can be set. Failures warn: the set that
     * follows reports the real problem, and a product is not a failed sync over one variant.
     */
    static void activateInventory(ShopifyGraphQLClient gql, String inventoryItemId, String locationId) {
        try {
            Map<String, Object> vars = Map.of("inventoryItemId", inventoryItemId,
                    "updates", List.of(Map.of("locationId", locationId, "activate", true)));
            JsonNode data = gql.execute(ShopifyGraphQL.INVENTORY_ACTIVATE, vars);
            checkUserErrors(require(data).path("inventoryBulkToggleActivation"),
                    "inventoryBulkToggleActivation");
        } catch (RuntimeException e) {
            log.warn("Could not stock inventory item {} at {}: {}", inventoryItemId, locationId,
                    e.getMessage());
        }
    }

    static JsonNode require(JsonNode data) {
        if (data == null) {
            throw new ShopifySyncException("Shopify returned no data");
        }
        return data;
    }

    static void checkUserErrors(JsonNode result, String op) {
        JsonNode userErrors = result.path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            throw new ShopifySyncException(op + " userErrors: " + userErrors);
        }
    }
}
