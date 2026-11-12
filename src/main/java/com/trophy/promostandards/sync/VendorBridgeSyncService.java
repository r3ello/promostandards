package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.discount.DiscountProperties;
import com.trophy.promostandards.discount.QuantityDiscountJson;
import com.trophy.promostandards.discount.QuantityLadder;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.vendorbridge.VendorBridgeClient;
import com.trophy.promostandards.vendorbridge.VendorBridgeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Syncs a store product from PaceSetter's <em>website</em> (VendorBridge) — for the products
 * PromoStandards no longer lists and the website still sells. Deliberately much less than the
 * PromoStandards sync: the website has no stock and its variants are not reliable, so this
 * <b>creates and deletes no variant, touches no inventory and never changes the status</b> (a draft
 * stays a draft). What it writes:
 * <ul>
 *   <li>each variant's price and quantity-discount ladder, from the page's price rows through the
 *       same {@link PricingPolicy} as every other published price;</li>
 *   <li>each variant's {@code trophy_sync.vendor_sku} and {@code custom.promo_standard_id} — its
 *       PaceSetter code, which is what an order to PaceSetter reads;</li>
 *   <li>on the <b>first</b> sync only, the page's title and description, and its photo when the
 *       product has none;</li>
 *   <li>{@code trophy_sync.vendor}, {@code source = vendorbridge} and {@code last_sync_at}.</li>
 * </ul>
 *
 * <p>{@link #preview} builds the same {@link Plan} {@link #sync} applies, and a plan with anything in
 * {@code blocking} is refused with nothing written. A code must be on the website as an <b>exact</b>
 * match and must find <b>its own variant</b>: a product listing several codes on one variant is left
 * for a review by hand, since one price cannot be right for all of them.
 */
@Service
public class VendorBridgeSyncService {

    private static final Logger log = LoggerFactory.getLogger(VendorBridgeSyncService.class);
    private static final String PRODUCT_GID = "gid://shopify/Product/";
    /** A price move of at least this share of the current price is pointed out in the preview. */
    private static final BigDecimal BIG_MOVE_PERCENT = BigDecimal.valueOf(30);

    private final ShopifyGraphQLClient gql;
    private final VendorBridgeClient bridge;
    private final VendorBridgeCatalogService catalog;
    private final PricingPolicy pricing;
    private final SyncProperties props;
    private final DiscountProperties discounts;
    private final ObjectMapper objectMapper;
    /** {@code trophy_sync.last_sync_at}'s type: text in the live store, corrected from a refusal. */
    private volatile String lastSyncAtType = "single_line_text_field";

    public VendorBridgeSyncService(ShopifyGraphQLClient gql, VendorBridgeClient bridge,
                                   VendorBridgeCatalogService catalog, PricingPolicy pricing, SyncProperties props,
                                   DiscountProperties discounts, ObjectMapper objectMapper) {
        this.gql = gql;
        this.bridge = bridge;
        this.catalog = catalog;
        this.pricing = pricing;
        this.props = props;
        this.discounts = discounts;
        this.objectMapper = objectMapper;
    }

    /**
     * One variant's part of the plan.
     *
     * @param code         the PaceSetter code the variant stands for
     * @param priceSku     the website's price row it is priced on (CD929A for CD929AY1G)
     * @param currentPrice what the store has now
     * @param newPrice     the first break's price, as published
     * @param minimumQuantity the first break's quantity
     * @param tiers        the cheaper breaks
     * @param discountJson the ladder's metafield value; null when there is no cheaper break
     */
    public record VariantPlan(String variantGid, String sku, String title, String code, String priceSku,
                              BigDecimal currentPrice, BigDecimal newPrice, int minimumQuantity,
                              List<QuantityLadder.Tier> tiers, String discountJson) {
    }

    /**
     * @param firstSync      the website sync has never run on it
     * @param currentTitle   the store's title now
     * @param newTitle       the title a sync writes; null when it keeps the store's
     * @param newDescriptionHtml same, for the description
     * @param imageToAdd     the page's photo, when the product has none and this is the first sync
     * @param blocking       why a sync is refused; empty = it can run
     * @param notices        what a sync will not do, and why
     */
    public record Plan(String productGid, String adminId, String handle, String status, boolean firstSync,
                       String currentTitle, String newTitle, String newDescriptionHtml, String imageToAdd,
                       String webUrl, List<VariantPlan> variants, List<String> blocking, List<String> notices) {
        public boolean isReady() {
            return blocking.isEmpty();
        }
    }

    /** What a sync did; {@code warnings} are the parts that failed without undoing the rest. */
    public record Result(Plan plan, int variantsUpdated, boolean contentUpdated, boolean imageAdded,
                         List<String> warnings) {
    }

    /** What a sync would write. Reads the store and the website; writes nothing. */
    public Plan preview(String adminId) {
        return plan(adminId);
    }

    /**
     * Applies the plan.
     *
     * @throws GroupConflictException when the plan is blocked — answered 409, nothing written
     */
    public Result sync(String adminId) {
        Plan plan = plan(adminId);
        if (!plan.isReady()) {
            throw new GroupConflictException("Website sync", plan.blocking());
        }
        List<String> warnings = new ArrayList<>();

        // Prices and the marks an order reads go first and must succeed: the rest is dressing.
        List<Map<String, Object>> variants = new ArrayList<>();
        for (VariantPlan v : plan.variants()) {
            List<Map<String, Object>> metafields = new ArrayList<>();
            metafields.add(variantMetafield(ShopifyProductMapper.SYNC_NAMESPACE,
                    ShopifyProductMapper.MF_VARIANT_VENDOR_SKU, "single_line_text_field", v.code()));
            metafields.add(variantMetafield(ShopifyProductMapper.METAFIELD_NAMESPACE,
                    ShopifyProductMapper.MF_VARIANT_PROMO_STANDARD_ID, "single_line_text_field", v.code()));
            if (v.discountJson() != null && discounts.isEnabled()) {
                metafields.add(variantMetafield(discounts.namespace(), discounts.key(), discounts.type(),
                        v.discountJson()));
            }
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("id", v.variantGid());
            variant.put("price", v.newPrice().toPlainString());
            variant.put("metafields", metafields);
            variants.add(variant);
        }
        ShopifySyncService.checkUserErrors(ShopifySyncService.require(gql.execute(ShopifyGraphQL.VARIANTS_BULK_UPDATE,
                Map.of("productId", plan.productGid(), "variants", variants))).path("productVariantsBulkUpdate"),
                "productVariantsBulkUpdate");

        boolean content = false;
        if (plan.newTitle() != null) {
            try {
                ShopifySyncService.checkUserErrors(gql.execute(ShopifyGraphQL.PRODUCT_CONTENT, Map.of(
                        "id", plan.productGid(), "title", plan.newTitle(),
                        "descriptionHtml", plan.newDescriptionHtml() == null ? "" : plan.newDescriptionHtml()))
                        .path("productUpdate"), "productUpdate");
                content = true;
            } catch (RuntimeException e) {
                warnings.add("The title and description were not updated: " + e.getMessage());
            }
        }
        boolean image = false;
        if (plan.imageToAdd() != null) {
            try {
                ShopifySyncService.checkUserErrors(gql.execute(ShopifyGraphQL.PRODUCT_ADD_MEDIA, Map.of(
                        "id", plan.productGid(), "media", List.of(Map.of("originalSource", plan.imageToAdd(),
                                "mediaContentType", "IMAGE",
                                "alt", plan.newTitle() != null ? plan.newTitle() : plan.currentTitle()))))
                        .path("productUpdate"), "productUpdate");
                image = true;
            } catch (RuntimeException e) {
                warnings.add("The photo was not added: " + e.getMessage());
            }
        }
        stamp(plan, warnings);
        catalog.markSynced(plan.adminId());
        log.info("Synced {} from PaceSetter's website: {} variant(s){}{}", plan.handle(), plan.variants().size(),
                content ? ", title and description" : "", image ? ", photo" : "");
        return new Result(plan, plan.variants().size(), content, image, warnings);
    }

    private Plan plan(String adminId) {
        if (adminId == null || !adminId.trim().matches("\\d+")) {
            throw new IllegalArgumentException("Not a Shopify product id: " + adminId);
        }
        String id = adminId.trim();
        List<String> blocking = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        if (!bridge.configured()) {
            blocking.add("VendorBridge is not configured (VENDORBRIDGE_KEY).");
        }
        JsonNode product = gql.execute(ShopifyGraphQL.VENDOR_BRIDGE_PRODUCT, Map.of("id", PRODUCT_GID + id))
                .path("product");
        if (product.isMissingNode() || product.isNull()) {
            throw new IllegalArgumentException("No store product " + id);
        }
        // The Status report is what says PromoStandards has nothing for it: a product it still
        // describes belongs to the normal sync, which knows its stock and its variants.
        Optional<VendorBridgeCatalogService.Candidate> candidate = catalog.candidate(id);
        if (candidate.isEmpty()) {
            blocking.add("Not a website product: the Status report does not list it as discontinued in "
                    + "PromoStandards with an exact match on PaceSetter's website. Rebuild the Status report "
                    + "if that changed.");
        }
        boolean firstSync = !CatalogStatusClassifier.WEBSITE_SOURCE
                .equalsIgnoreCase(text(product.path("source").path("value")));
        List<String> codes = codes(product);
        if (codes.isEmpty()) {
            blocking.add("The product lists no PaceSetter code.");
        }

        Map<String, VendorBridgeClient.Page> pages = new LinkedHashMap<>();
        Map<String, List<VendorBridgeClient.PriceTier>> rows = new HashMap<>();
        if (bridge.configured()) {
            for (String code : codes) {
                try {
                    Optional<VendorBridgeClient.Page> page = bridge.page(code);
                    if (page.isEmpty()) {
                        blocking.add(code + " is not on PaceSetter's website.");
                    } else if (!page.get().listing().exact()) {
                        blocking.add(code + " is on the website only as a " + page.get().listing().matchedBy()
                                + " match (" + page.get().listing().matchedSku() + "): it may be another model.");
                    } else {
                        pages.put(code, page.get());
                        rows.put(code, bridge.discounts(code));
                    }
                } catch (VendorBridgeException e) {
                    blocking.add("VendorBridge could not answer for " + code + ": " + e.getMessage());
                }
            }
        }

        List<JsonNode> storeVariants = new ArrayList<>();
        product.path("variants").path("nodes").forEach(storeVariants::add);
        List<VariantPlan> variants = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String code : pages.keySet()) {
            JsonNode variant = variantOf(code, codes, storeVariants);
            if (variant == null) {
                blocking.add(code + " has no variant of its own (" + storeVariants.size() + " variant(s) for "
                        + codes.size() + " codes): the product needs a review by hand.");
                continue;
            }
            if (!used.add(variant.path("id").asText())) {
                blocking.add(code + " and another code point at the same variant.");
                continue;
            }
            String priceSku = priceSku(code, rows.get(code));
            QuantityLadder ladder = priceSku == null ? null : ladder(code, priceSku, rows.get(code));
            if (ladder == null) {
                blocking.add("The website has no price for " + code + ".");
                continue;
            }
            String json = ladder.hasDiscounts() ? QuantityDiscountJson.of(ladder, props.currency()).toJson(objectMapper)
                    : null;
            variants.add(new VariantPlan(variant.path("id").asText(), text(variant.path("sku")),
                    text(variant.path("title")), code, priceSku, money(variant.path("price")), ladder.basePrice(),
                    ladder.minimumQuantity(), ladder.tiers(), json));
            // The store's price is the migration's, years old, so a move is expected — but a third or
            // more is also what a page that is not this product looks like (Blue Sail: 285.89 -> 84.99).
            BigDecimal current = money(variant.path("price"));
            if (current != null && current.signum() > 0) {
                BigDecimal move = ladder.basePrice().subtract(current).abs();
                if (move.multiply(BigDecimal.valueOf(100)).compareTo(current.multiply(BIG_MOVE_PERCENT)) >= 0) {
                    notices.add(code + ": the price moves from " + current.toPlainString() + " to "
                            + ladder.basePrice().toPlainString() + ". Check the website page is this product "
                            + "before syncing.");
                }
            }
            if (!priceSku.equalsIgnoreCase(code)) {
                notices.add(code + " is priced on the website's row " + priceSku + ".");
            }
        }
        long untouched = storeVariants.stream().filter(v -> !used.contains(v.path("id").asText())).count();
        if (untouched > 0 && !variants.isEmpty()) {
            notices.add(untouched + " variant(s) stand for no code of the product and are left as they are.");
        }

        // Title and description: the first sync's, and only when the page is this product's alone.
        String newTitle = null;
        String newDescription = null;
        String image = null;
        String webUrl = candidate.map(VendorBridgeCatalogService.Candidate::webUrl).orElse(null);
        Set<String> distinctPages = new LinkedHashSet<>();
        pages.values().forEach(p -> distinctPages.add(p.listing().url()));
        if (!pages.isEmpty()) {
            VendorBridgeClient.Page first = pages.values().iterator().next();
            webUrl = first.listing().url() != null ? first.listing().url() : webUrl;
            int shared = candidate.map(VendorBridgeCatalogService.Candidate::sharedWith).orElse(0);
            if (!firstSync) {
                notices.add("Synced from the website before: title, description and photos are left as they are.");
            } else if (shared > 0) {
                notices.add("Title and description are kept: the website page is shared with " + shared
                        + " other store product(s), so its wording describes them all.");
            } else if (distinctPages.size() > 1) {
                notices.add("Title and description are kept: its codes are on " + distinctPages.size()
                        + " different website pages.");
            } else if (first.listing().title() != null) {
                newTitle = first.listing().title();
                newDescription = first.descriptionHtml();
            }
            if (firstSync && product.path("media").path("nodes").isEmpty() && blocking.isEmpty()) {
                try {
                    image = bridge.images(pages.keySet().iterator().next()).stream().findFirst().orElse(null);
                } catch (VendorBridgeException e) {
                    notices.add("The website's photo could not be read: " + e.getMessage());
                }
            }
        }
        if (variants.isEmpty() && blocking.isEmpty()) {
            blocking.add("Nothing to sync.");
        }
        return new Plan(product.path("id").asText(), id, text(product.path("handle")), text(product.path("status")),
                firstSync, text(product.path("title")), newTitle, newDescription, image, webUrl, variants,
                blocking, notices);
    }

    /**
     * The variant a code stands for: the one marked with it ({@code vendor_sku}, then
     * {@code promo_standard_id}); a product of one code and one variant needs no mark.
     */
    private static JsonNode variantOf(String code, List<String> codes, List<JsonNode> variants) {
        for (String field : List.of("vendorSku", "psVariantId")) {
            for (JsonNode v : variants) {
                String mark = text(v.path(field).path("value"));
                if (mark != null && mark.trim().equalsIgnoreCase(code)) {
                    return v;
                }
            }
        }
        return codes.size() == 1 && variants.size() == 1 ? variants.get(0) : null;
    }

    /** The price row a code is sold on: its own, else the longest row SKU the code starts with. */
    static String priceSku(String code, List<VendorBridgeClient.PriceTier> rows) {
        if (rows == null) {
            return null;
        }
        String upper = code.toUpperCase(Locale.ROOT);
        return rows.stream().map(VendorBridgeClient.PriceTier::sku).distinct()
                .filter(sku -> upper.startsWith(sku.toUpperCase(Locale.ROOT)))
                .max(Comparator.comparingInt(String::length)).orElse(null);
    }

    /** The website's rows as the supplier price table every other ladder is built from. */
    private QuantityLadder ladder(String code, String priceSku, List<VendorBridgeClient.PriceTier> rows) {
        List<Configuration.PriceBreak> breaks = rows.stream().filter(r -> r.sku().equalsIgnoreCase(priceSku))
                .map(r -> new Configuration.PriceBreak(r.minQuantity(), null, r.unitPrice(), null)).toList();
        return QuantityLadder.of(List.of(new Configuration.PartPrice(code, null, breaks)), code, pricing);
    }

    private void stamp(Plan plan, List<String> warnings) {
        List<Map<String, Object>> metafields = new ArrayList<>();
        metafields.add(productMetafield(plan.productGid(), ShopifyProductMapper.SYNC_NAMESPACE,
                ShopifyProductMapper.MF_VENDOR, "single_line_text_field", props.supplierCode()));
        metafields.add(productMetafield(plan.productGid(), ShopifyProductMapper.SYNC_NAMESPACE,
                ShopifyProductMapper.MF_SOURCE_NEW, "single_line_text_field", CatalogStatusClassifier.WEBSITE_SOURCE));
        // One ladder for the whole product is what the console's badge and a product-level reader see;
        // different ladders stay on their own variants only.
        Set<String> ladders = new HashSet<>();
        plan.variants().forEach(v -> ladders.add(String.valueOf(v.discountJson())));
        String shared = ladders.size() == 1 ? plan.variants().get(0).discountJson() : null;
        if (shared != null && discounts.isEnabled()) {
            metafields.add(productMetafield(plan.productGid(), discounts.namespace(), discounts.key(),
                    discounts.type(), shared));
        }
        try {
            ShopifySyncService.checkUserErrors(gql.execute(ShopifyGraphQL.METAFIELDS_SET,
                    Map.of("metafields", metafields)).path("metafieldsSet"), "metafieldsSet");
        } catch (RuntimeException e) {
            warnings.add("The product was not marked as synced from the website: " + e.getMessage());
        }
        // On its own: one metafieldsSet is all or nothing, and this is the one whose type varies by store.
        try {
            stampLastSync(plan.productGid());
        } catch (RuntimeException e) {
            String defined = ShopifySyncService.definedTypeOfFirst(e.getMessage());
            if (defined == null || defined.equals(lastSyncAtType)) {
                warnings.add("The sync date was not written: " + e.getMessage());
                return;
            }
            lastSyncAtType = defined;
            try {
                stampLastSync(plan.productGid());
            } catch (RuntimeException again) {
                warnings.add("The sync date was not written: " + again.getMessage());
            }
        }
    }

    private void stampLastSync(String productGid) {
        ShopifySyncService.checkUserErrors(gql.execute(ShopifyGraphQL.METAFIELDS_SET, Map.of("metafields",
                List.of(productMetafield(productGid, ShopifyProductMapper.SYNC_NAMESPACE,
                        ShopifyProductMapper.MF_LAST_SYNC_AT_NEW, lastSyncAtType,
                        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())))).path("metafieldsSet"),
                "metafieldsSet");
    }

    /** {@code ps_product_ids}, else the single {@code ps_product_id}; as the store spells them. */
    private List<String> codes(JsonNode product) {
        List<String> codes = new ArrayList<>();
        String list = text(product.path("psIds").path("value"));
        if (list != null) {
            try {
                for (JsonNode code : objectMapper.readTree(list)) {
                    add(codes, code.asText(""));
                }
            } catch (IOException e) {
                log.debug("Unreadable ps_product_ids on {}: {}", product.path("handle").asText(), list);
            }
        }
        if (codes.isEmpty()) {
            add(codes, text(product.path("psId").path("value")));
        }
        return codes;
    }

    private static void add(List<String> codes, String code) {
        String value = code == null ? "" : code.trim();
        if (!value.isEmpty() && codes.stream().noneMatch(value::equalsIgnoreCase)) {
            codes.add(value);
        }
    }

    private static Map<String, Object> variantMetafield(String namespace, String key, String type, String value) {
        return Map.of("namespace", namespace, "key", key, "type", type, "value", value);
    }

    private static Map<String, Object> productMetafield(String ownerId, String namespace, String key, String type,
                                                        String value) {
        return Map.of("ownerId", ownerId, "namespace", namespace, "key", key, "type", type,
                "value", value == null ? "" : value);
    }

    private static BigDecimal money(JsonNode node) {
        String s = text(node);
        try {
            return s == null ? null : new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        String s = node.isMissingNode() || node.isNull() ? null : node.asText(null);
        return s == null || s.isBlank() ? null : s;
    }
}
