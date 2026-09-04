package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.ForeignVariantPlan.Action;
import com.trophy.promostandards.sync.ForeignVariantPlan.Entry;
import com.trophy.promostandards.sync.ForeignVariantPlan.StoreVariant;
import com.trophy.promostandards.sync.ShopifySyncService.ImportedProduct;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.trophy.promostandards.sync.ShopifySyncService.checkUserErrors;
import static com.trophy.promostandards.sync.ShopifySyncService.require;

/**
 * Brings a store product this app did not create up to the supplier's variant list.
 *
 * <p>These are the products the one-shot trophypartner migration created: they carry the
 * PromoStandards identity metafields ({@code ps_product_id}, the N:1 {@code ps_product_ids} list,
 * {@code ps_source=migration}) but a single legacy variant — {@code Title / Default Title}, a legacy
 * SKU, untracked inventory. Matching the supplier's variants by SKU therefore matches nothing, which
 * is why syncing one used to be a no-op.
 *
 * <p>What happens instead, without ever calling {@code productSet} (declarative: it would delete the
 * variants of every other supplier id the product covers, and overwrite the migrated title, body and
 * images):
 * <ol>
 *   <li>the supplier variants are the <b>union</b> over every id in {@code ps_product_ids} — a
 *       grouped product like {@code CM297} is one Shopify product per ten PaceSetter ids;</li>
 *   <li>the product's {@code Title} option is renamed to {@code Color} (and {@code Size} added) in
 *       place, so the existing variant survives;</li>
 *   <li>that lone variant is <b>adopted</b> as the first supplier variant — its id, and with it the
 *       order history, is kept — while the rest are created;</li>
 *   <li>each variant is stamped with {@code custom.promo_standard_id} = its own supplier id, which
 *       is what makes the next sync match without relying on SKUs at all.</li>
 * </ol>
 */
class ForeignProductSync {

    private static final Logger log = LoggerFactory.getLogger(ForeignProductSync.class);

    /** Option names this app knows how to write. Anything else means hands off the variants. */
    private static final Set<String> KNOWN_OPTIONS = Set.of("title", "color", "size");

    private final ShopifyGraphQLClient gql;
    private final CatalogService catalog;
    private final PricingPolicy pricingPolicy;
    private final ShopifyProperties shopify;
    private final SyncProperties props;
    private final ObjectMapper objectMapper;

    ForeignProductSync(ShopifyGraphQLClient gql, CatalogService catalog, PricingPolicy pricingPolicy,
                       ShopifyProperties shopify, SyncProperties props, ObjectMapper objectMapper) {
        this.gql = gql;
        this.catalog = catalog;
        this.pricingPolicy = pricingPolicy;
        this.shopify = shopify;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * @param variants         supplier variants the product now stands for
     * @param created          how many of them did not exist in Shopify before
     * @param inventoryUpdated variants whose on-hand quantity was pushed
     * @param supplierIdsAdded ids discovered in the supplier's data and appended to
     *                         {@code ps_product_ids} (the catalog index must be rebuilt)
     */
    record Result(int variants, int created, int inventoryUpdated, List<String> supplierIdsAdded) {
    }

    /**
     * @param seed         the aggregate of the supplier id the caller asked to sync
     * @param storeProduct the store product node (see {@code PRODUCT_BY_HANDLE})
     * @param imported     the index entry behind it, carrying every supplier id it covers
     */
    Result sync(String productId, SupplierProduct seed, JsonNode storeProduct, ImportedProduct imported) {
        String gid = storeProduct.path("id").asText();
        Union unioned = union(seed, imported);
        SupplierProduct union = unioned.product();
        List<StoreVariant> store = storeVariants(storeProduct);
        Map<String, JsonNode> options = optionsByName(storeProduct);

        List<String> colorLabels = VariantOptions.colorLabels(union.variants());
        boolean emitSize = VariantOptions.hasSize(union.variants()) || options.containsKey("size");
        ForeignVariantPlan plan = ForeignVariantPlan.of(union.variants(), colorLabels, emitSize, store);

        boolean writable = writableShape(productId, gid, options, store);
        if (writable) {
            ensureOptions(gid, options, plan, emitSize);
        }
        int updated = updateExisting(gid, plan, writable, emitSize);
        int createdCount = writable ? createMissing(gid, plan, emitSize) : 0;
        int inventory = pushInventory(gid, plan, writable);
        List<String> added = extendSupplierIds(gid, imported, unioned.discoveredIds());

        log.info("Synced foreign product {} -> {}: {} supplier variants ({} created, {} updated, "
                        + "{} inventory rows{})",
                productId, gid, plan.entries().size(), createdCount, updated, inventory,
                added.isEmpty() ? "" : ", +" + added.size() + " supplier ids");
        return new Result(plan.entries().size(), createdCount, inventory, added);
    }

    // ---------------------------------------------------------------- supplier side

    /**
     * The variants of every supplier id the store product covers, keyed by (part id, size).
     *
     * <p>Each id is aggregated on its own so its prices are its own, but PaceSetter's Inventory
     * service answers for a whole family at once ({@code CM297BL} returns all twelve {@code CM297*}
     * rows), so the union also picks up parts nobody asked for — which is the point: they are
     * variants of this product too. A part that its own id also reports wins over the family copy.
     */
    private Union union(SupplierProduct seed, ImportedProduct imported) {
        Map<String, Variant> byKey = new LinkedHashMap<>();
        Set<String> authoritative = new LinkedHashSet<>();
        Set<String> known = new LinkedHashSet<>();
        known.add(upper(seed.productId()));
        collect(byKey, authoritative, seed.productId(), seed.variants());
        if (imported != null) {
            for (String id : imported.supplierIds()) {
                if (!known.add(upper(id))) {
                    continue;
                }
                aggregateInto(byKey, authoritative, id, seed.productId());
            }
        }

        // Parts the family answer turned up that no listed id covers (PaceSetter answers inventory
        // for the whole CM297* family, ps_product_ids lists ten of the twelve). A part that the
        // supplier also serves as a product of its own is one of these products' ids: aggregate it
        // for its own prices and record it. One that has no product of its own stays a variant only.
        List<String> discovered = new ArrayList<>();
        for (String partId : List.copyOf(partIds(byKey.values()))) {
            if (!known.add(upper(partId))) {
                continue;
            }
            if (aggregateInto(byKey, authoritative, partId, seed.productId())) {
                discovered.add(partId);
            }
        }

        SupplierProduct product = new SupplierProduct(seed.productId(), seed.title(),
                seed.descriptionHtml(), seed.vendor(), seed.productType(), seed.tags(),
                List.copyOf(byKey.values()), seed.imageUrls(), seed.priceParts(), seed.warnings());
        return new Union(product, List.copyOf(discovered));
    }

    /** The union so far, plus the supplier ids it turned up that {@code ps_product_ids} did not list. */
    private record Union(SupplierProduct product, List<String> discoveredIds) {
    }

    /** @return whether the supplier serves {@code id} as a product of its own. */
    private boolean aggregateInto(Map<String, Variant> byKey, Set<String> authoritative, String id,
                                  String forProduct) {
        try {
            collect(byKey, authoritative, id, catalog.aggregate(id).variants());
            return true;
        } catch (RuntimeException e) {
            // Not every part id is a product id, and an id the supplier no longer serves must not
            // sink the whole product: either way this one contributes no prices of its own.
            log.debug("No product of its own for {} while unioning {}: {}", id, forProduct,
                    e.getMessage());
            return false;
        }
    }

    private Set<String> partIds(java.util.Collection<Variant> variants) {
        Set<String> ids = new LinkedHashSet<>();
        for (Variant v : variants) {
            if (v.supplierPartId() != null && !v.supplierPartId().isBlank()) {
                ids.add(v.supplierPartId());
            }
        }
        return ids;
    }

    private void collect(Map<String, Variant> byKey, Set<String> authoritative, String sourceId,
                         List<Variant> variants) {
        for (Variant v : variants) {
            Variant variant = withPartId(v, sourceId);
            String key = upper(variant.supplierPartId()) + "|" + upper(variant.size());
            boolean own = variant.supplierPartId().equalsIgnoreCase(sourceId);
            if (!byKey.containsKey(key) || (own && !authoritative.contains(key))) {
                // The id's own aggregate carries its own prices; a family-wide copy is the fallback.
                byKey.put(key, variant);
            }
            if (own) {
                authoritative.add(key);
            }
        }
    }

    /** A variant the supplier gave no part id for stands for the id it was fetched under. */
    private Variant withPartId(Variant v, String sourceId) {
        if (v.supplierPartId() != null && !v.supplierPartId().isBlank()) {
            return v;
        }
        boolean sized = props.skuStrategy() != SyncProperties.SkuStrategy.PART
                && v.size() != null && !v.size().isBlank();
        String sku = sized ? sourceId + "-" + v.size() : sourceId;
        return new Variant(sourceId, v.color(), v.size(), sku, v.supplierNet(), v.listPrice(),
                v.onHand(), v.imageUrls());
    }

    // ---------------------------------------------------------------- store side

    private List<StoreVariant> storeVariants(JsonNode storeProduct) {
        List<StoreVariant> variants = new ArrayList<>();
        for (JsonNode n : storeProduct.path("variants").path("nodes")) {
            String color = null;
            String size = null;
            for (JsonNode o : n.path("selectedOptions")) {
                String name = o.path("name").asText("");
                if (VariantOptions.COLOR.equalsIgnoreCase(name)) {
                    color = o.path("value").asText(null);
                } else if (VariantOptions.SIZE.equalsIgnoreCase(name)) {
                    size = o.path("value").asText(null);
                }
            }
            JsonNode item = n.path("inventoryItem");
            variants.add(new StoreVariant(n.path("id").asText(null), n.path("sku").asText(null),
                    n.path("psId").path("value").asText(null), color, size,
                    item.path("id").asText(null), item.path("tracked").asBoolean(false),
                    ShopifySyncService.availableAt(item)));
        }
        return variants;
    }

    private Map<String, JsonNode> optionsByName(JsonNode storeProduct) {
        Map<String, JsonNode> options = new LinkedHashMap<>();
        for (JsonNode o : storeProduct.path("options")) {
            options.put(o.path("name").asText("").toLowerCase(Locale.ROOT), o);
        }
        return options;
    }

    /**
     * @return whether this product's option structure is one we may rewrite. A product built around
     * options we do not model (Material, Style, …) keeps its variants untouched — only the prices and
     * quantities of variants that already match are refreshed.
     */
    private boolean writableShape(String productId, String gid, Map<String, JsonNode> options,
                                  List<StoreVariant> store) {
        List<String> unknown = options.keySet().stream().filter(n -> !KNOWN_OPTIONS.contains(n)).toList();
        if (!unknown.isEmpty()) {
            log.warn("Store product {} ({}) uses options {} this app does not model; leaving its "
                    + "variants alone and only refreshing matched ones", gid, productId, unknown);
            return false;
        }
        if (options.containsKey("title") && store.size() > 1) {
            log.warn("Store product {} ({}) still has the default Title option across {} variants; "
                    + "leaving its variants alone", gid, productId, store.size());
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- mutations

    /** Turns the migrated {@code Title} option into {@code Color}, and adds {@code Size} if needed. */
    private void ensureOptions(String gid, Map<String, JsonNode> options, ForeignVariantPlan plan,
                               boolean emitSize) {
        Entry first = plan.adopted() != null ? plan.adopted()
                : plan.entries().isEmpty() ? null : plan.entries().get(0);
        if (first == null) {
            return;
        }
        if (!options.containsKey("color")) {
            JsonNode title = options.get("title");
            if (title != null) {
                List<Map<String, Object>> valuesToUpdate = new ArrayList<>();
                JsonNode values = title.path("optionValues");
                if (values.size() == 1) {
                    valuesToUpdate.add(Map.of("id", values.get(0).path("id").asText(),
                            "name", first.colorLabel()));
                }
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put("productId", gid);
                vars.put("option", Map.of("id", title.path("id").asText(), "name", VariantOptions.COLOR));
                vars.put("optionValuesToUpdate", valuesToUpdate);
                vars.put("variantStrategy", "LEAVE_AS_IS");
                checkUserErrors(require(gql.execute(ShopifyGraphQL.PRODUCT_OPTION_UPDATE, vars))
                        .path("productOptionUpdate"), "productOptionUpdate");
            } else {
                createOption(gid, VariantOptions.COLOR, 1, first.colorLabel());
            }
        }
        if (emitSize && !options.containsKey("size")) {
            createOption(gid, VariantOptions.SIZE, 2, VariantOptions.size(first.sizeLabel()));
        }
    }

    private void createOption(String gid, String name, int position, String firstValue) {
        Map<String, Object> option = Map.of("name", name, "position", position,
                "values", List.of(Map.of("name", firstValue)));
        Map<String, Object> vars = Map.of("productId", gid, "options", List.of(option),
                "variantStrategy", "LEAVE_AS_IS");
        checkUserErrors(require(gql.execute(ShopifyGraphQL.PRODUCT_OPTIONS_CREATE, vars))
                .path("productOptionsCreate"), "productOptionsCreate");
    }

    /**
     * Rewrites the variants that already exist: price, the identity metafield, tracked inventory,
     * and — for the adopted legacy variant — its SKU and option values.
     */
    private int updateExisting(String gid, ForeignVariantPlan plan, boolean writable, boolean emitSize) {
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Entry e : plan.toUpdate()) {
            if (!writable && e.action() == Action.ADOPT) {
                continue;       // adoption rewrites option values; not on a shape we do not model
            }
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("id", e.target().id());
            BigDecimal price = pricingPolicy.retailPrice(e.variant().supplierNet(), e.variant().listPrice());
            if (price != null) {
                variant.put("price", price.toPlainString());
            }
            if (writable) {
                // The SKU is the supplier's from here on: a migrated PS-prefixed SKU matches nothing.
                variant.put("inventoryItem", Map.of("sku", e.variant().sku(), "tracked", true));
                variant.put("optionValues", optionValues(e, emitSize));
            }
            variant.put("metafields", List.of(promoStandardId(e.supplierId())));
            variants.add(variant);
        }
        if (variants.isEmpty()) {
            return 0;
        }
        JsonNode data = gql.execute(ShopifyGraphQL.VARIANTS_BULK_UPDATE,
                Map.of("productId", gid, "variants", variants));
        checkUserErrors(require(data).path("productVariantsBulkUpdate"), "productVariantsBulkUpdate");
        return variants.size();
    }

    /** Creates the supplier variants the product is missing, with stock set at creation time. */
    private int createMissing(String gid, ForeignVariantPlan plan, boolean emitSize) {
        List<Entry> missing = plan.toCreate();
        if (missing.isEmpty()) {
            return 0;
        }
        String locationId = shopify.locationId();
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Entry e : missing) {
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("optionValues", optionValues(e, emitSize));
            BigDecimal price = pricingPolicy.retailPrice(e.variant().supplierNet(), e.variant().listPrice());
            if (price != null) {
                variant.put("price", price.toPlainString());
            }
            variant.put("inventoryItem", Map.of("sku", e.variant().sku(), "tracked", true));
            variant.put("metafields", List.of(promoStandardId(e.supplierId())));
            if (locationId != null && !locationId.isBlank() && e.variant().onHand() != null) {
                // Only valid on create — it activates the item at the location and sets the quantity.
                variant.put("inventoryQuantities", List.of(Map.of(
                        "locationId", locationId, "availableQuantity", e.variant().onHand())));
            }
            variants.add(variant);
        }
        Map<String, Object> vars = Map.of("productId", gid, "variants", variants,
                "strategy", "PRESERVE_STANDALONE_VARIANT");
        JsonNode data = gql.execute(ShopifyGraphQL.VARIANTS_BULK_CREATE, vars);
        checkUserErrors(require(data).path("productVariantsBulkCreate"), "productVariantsBulkCreate");
        return variants.size();
    }

    private List<Map<String, String>> optionValues(Entry e, boolean emitSize) {
        List<Map<String, String>> values = new ArrayList<>();
        values.add(Map.of("optionName", VariantOptions.COLOR, "name", e.colorLabel()));
        if (emitSize) {
            values.add(Map.of("optionName", VariantOptions.SIZE, "name", VariantOptions.size(e.sizeLabel())));
        }
        return values;
    }

    private Map<String, Object> promoStandardId(String supplierId) {
        return Map.of("namespace", ShopifyProductMapper.METAFIELD_NAMESPACE,
                "key", ShopifyProductMapper.MF_VARIANT_PROMO_STANDARD_ID,
                "type", "single_line_text_field",
                "value", supplierId == null ? "" : supplierId);
    }

    /**
     * Pushes on-hand quantities for the variants that already existed (created ones got theirs at
     * creation). A migrated variant is stocked nowhere, so it is activated at the location first —
     * {@code inventorySetQuantities} rejects an item the location does not carry — and the store's
     * current quantity rides along as the compare-and-set baseline the API requires.
     * Inventory failures warn: a product whose variants are now right is not a failed sync.
     */
    private int pushInventory(String gid, ForeignVariantPlan plan, boolean writable) {
        String locationId = shopify.locationId();
        if (locationId == null || locationId.isBlank()) {
            log.warn("shopify.location-id not set; skipping inventory for {}", gid);
            return 0;
        }
        List<Map<String, Object>> quantities = new ArrayList<>();
        for (Entry e : plan.toUpdate()) {
            StoreVariant target = e.target();
            if (e.variant().onHand() == null || target.inventoryItemId() == null
                    || (!writable && e.action() == Action.ADOPT)) {
                continue;
            }
            if (target.available() == null) {
                // A migrated variant is stocked nowhere, so the location has to carry it first.
                ShopifySyncService.activateInventory(gql, target.inventoryItemId(), locationId);
            }
            quantities.add(Map.of(
                    "inventoryItemId", target.inventoryItemId(),
                    "locationId", locationId,
                    "quantity", e.variant().onHand(),
                    // Mandatory compare-and-set baseline since API 2026-04.
                    "changeFromQuantity", target.available() == null ? 0 : target.available()));
        }
        if (quantities.isEmpty()) {
            return 0;
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "available");
        input.put("reason", "correction");
        input.put("quantities", quantities);
        try {
            // A fresh key per push: the client's throttling retry resends the same body, and
            // Shopify must count that as the one write it is.
            Map<String, Object> vars = Map.of("input", input,
                    "idempotencyKey", UUID.randomUUID().toString());
            JsonNode data = gql.execute(ShopifyGraphQL.INVENTORY_SET_QUANTITIES, vars);
            checkUserErrors(require(data).path("inventorySetQuantities"), "inventorySetQuantities");
            return quantities.size();
        } catch (RuntimeException ex) {
            log.warn("Could not set inventory on {}: {}", gid, ex.getMessage());
            return 0;
        }
    }

    /**
     * Appends supplier ids the union discovered (a family member the migration did not list, e.g.
     * {@code CM297BB}) to {@code ps_product_ids}, so the catalog counts them as imported and the next
     * sync unions them too. The canonical {@code ps_product_id} is left alone.
     *
     * @return the ids added, empty when the list already covered everything
     */
    private List<String> extendSupplierIds(String gid, ImportedProduct imported, List<String> discovered) {
        Map<String, String> byKey = new LinkedHashMap<>();
        if (imported != null) {
            for (String id : imported.supplierIds()) {
                byKey.putIfAbsent(upper(id), id);
            }
        }
        List<String> added = new ArrayList<>();
        for (String id : discovered) {
            if (byKey.putIfAbsent(upper(id), id) == null) {
                added.add(id);
            }
        }
        if (added.isEmpty() || imported == null) {
            return List.of();
        }
        try {
            String value = objectMapper.writeValueAsString(byKey.values());
            Map<String, Object> metafield = Map.of(
                    "ownerId", gid,
                    "namespace", ShopifyProductMapper.METAFIELD_NAMESPACE,
                    "key", ShopifyProductMapper.MF_PRODUCT_IDS,
                    "type", "list.single_line_text_field",
                    "value", value);
            JsonNode data = gql.execute(ShopifyGraphQL.METAFIELDS_SET,
                    Map.of("metafields", List.of(metafield)));
            checkUserErrors(require(data).path("metafieldsSet"), "metafieldsSet");
            log.info("Extended ps_product_ids on {} with {}", gid, added);
            return List.copyOf(added);
        } catch (Exception e) {
            log.warn("Could not extend ps_product_ids on {}: {}", gid, e.getMessage());
            return List.of();
        }
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
