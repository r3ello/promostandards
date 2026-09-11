package com.trophy.promostandards.discount;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.sync.CatalogService;
import com.trophy.promostandards.sync.PricingPolicy;
import com.trophy.promostandards.sync.ShopifySyncService;
import com.trophy.promostandards.sync.SyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Publishes a supplier product's quantity price table as JSON in a Shopify metafield.
 *
 * <p>A Shopify variant carries one price — the first break's — so every cheaper break has to be
 * published somewhere the storefront can read it. That somewhere is one metafield
 * ({@link DiscountProperties}, {@code trophy_discount.discount_tiers}) holding the whole
 * ladder; the store's discount app reads it from there. Nothing is called, so a publish is a single
 * idempotent write: run it again and the same value lands again.
 *
 * <p><b>One value is not always enough.</b> A grouped Shopify product covers several PaceSetter ids
 * and PaceSetter prices each id on its own table: EP2 and EP2PK live in one product at $345.99 and
 * $634.99, so one product-wide ladder is wrong for one of them. Ids are therefore grouped by the
 * exact JSON they produce, and each group's value is written on <b>its own variants</b> — matched
 * through {@code custom.promo_standard_id}. The product-level metafield is written only when a
 * single ladder covers every id the product stands for; it is what the catalog badge reads.
 */
@Service
public class DiscountSyncService {

    private static final Logger log = LoggerFactory.getLogger(DiscountSyncService.class);

    private final CatalogService catalog;
    private final PricingPolicy pricingPolicy;
    private final ShopifySyncService shopifySync;
    private final SyncProperties syncProps;
    private final DiscountProperties props;
    private final ObjectMapper mapper;

    public DiscountSyncService(CatalogService catalog, PricingPolicy pricingPolicy,
                               ShopifySyncService shopifySync, SyncProperties syncProps,
                               DiscountProperties props, ObjectMapper mapper) {
        this.catalog = catalog;
        this.pricingPolicy = pricingPolicy;
        this.shopifySync = shopifySync;
        this.syncProps = syncProps;
        this.props = props;
        this.mapper = mapper;
    }

    /** What a discount sync did, or why it did nothing. */
    public enum Outcome {
        /** The ladder is now in the metafield(s) — first write or overwrite, it is the same write. */
        PUBLISHED,
        /** The supplier gives no cheaper break, so there is no quantity discount to publish. */
        NO_DISCOUNTS,
        /**
         * There is a ladder, but nowhere in the store to put it: no variant carries
         * {@code custom.promo_standard_id} and no single ladder covers the whole product. The usual
         * cause is a migrated product still on its one legacy variant — import it first.
         */
        NOT_WRITTEN,
        /** Only a preview was asked for; nothing was written. */
        PREVIEWED,
        /** The feature is switched off ({@code discounts.enabled=false}). */
        SKIPPED
    }

    /**
     * One published ladder.
     *
     * @param supplierIds the ids that produce this exact ladder
     * @param variantGids the variants it was written on
     * @param json        the exact metafield value
     */
    public record VariantDiscount(List<String> supplierIds, List<String> variantGids,
                                  BigDecimal basePrice, List<QuantityLadder.Tier> tiers, String json) {
    }

    /**
     * @param metafield   the {@code namespace.key} written to, so a caller can check it against the
     *                    store without reading the configuration
     * @param productWide whether the product-level metafield was written too (one ladder covers all)
     * @param variants    one entry per distinct ladder
     * @param warnings    things a human should know, never a reason to fail the sync
     */
    public record DiscountResult(String productId, Outcome outcome, String reason,
                                 BigDecimal basePrice, int minimumQuantity,
                                 List<QuantityLadder.Tier> tiers, String metafield,
                                 boolean productWide, List<VariantDiscount> variants,
                                 List<String> warnings) {
    }

    /** Writes the ladder(s) this product needs onto its variants and, when it can, the product. */
    public DiscountResult sync(String productId) {
        if (!props.isEnabled()) {
            return skipped(productId, "discount publishing is disabled (discounts.enabled=false)");
        }
        Plan plan = plan(productId, true);
        if (plan.groups().isEmpty()) {
            return noDiscounts(productId, plan);
        }

        List<String> warnings = new ArrayList<>(plan.warnings());
        List<VariantDiscount> written = new ArrayList<>();
        QuantityLadder primary = plan.groups().get(0).ladder();
        try {
            for (Group group : plan.groups()) {
                if (group.variantGids().isEmpty()) {
                    warnings.add("no Shopify variant carries custom.promo_standard_id for "
                            + group.supplierIds() + ", so its ladder was not written per variant");
                    continue;
                }
                shopifySync.setVariantMetafield(plan.productGid(), group.variantGids(),
                        props.namespace(), props.key(), props.type(), group.json());
                written.add(new VariantDiscount(group.supplierIds(), group.variantGids(),
                        group.ladder().basePrice(), group.ladder().tiers(), group.json()));
            }
        } catch (RuntimeException e) {
            if (!refusedNamespace(e)) {
                throw e;
            }
            return refused(productId, primary, warnings, e);
        }
        if (written.isEmpty() && !plan.productWide()) {
            // Nothing was written, so saying PUBLISHED would be a lie — and it was one: a migrated
            // product still on its single legacy variant carries no custom.promo_standard_id, so
            // there is nothing to match a ladder to, and a partly-priced product gets no product-wide
            // value either. Importing it first is what creates the variants and stamps their ids.
            return new DiscountResult(productId, Outcome.NOT_WRITTEN,
                    "no store variant carries custom.promo_standard_id and no single ladder covers "
                            + "every id of this product, so there was nowhere to write it — import "
                            + "the product first (that is what stamps each variant with its id)",
                    primary.basePrice(), primary.minimumQuantity(), primary.tiers(),
                    props.qualifiedName(), false, List.of(), List.copyOf(warnings));
        }

        if (plan.productWide()) {
            try {
                shopifySync.setProductMetafield(plan.productGid(), props.namespace(), props.key(),
                        props.type(), plan.groups().get(0).json());
            } catch (RuntimeException e) {
                if (!refusedNamespace(e)) {
                    throw e;
                }
                return refused(productId, primary, warnings, e);
            }
        }
        // The catalog badge reads the product metafield off the cached store index; without this it
        // would keep saying "no discounts" about a product that has just been given them.
        shopifySync.invalidateImportedIndex();

        log.info("Published quantity discounts for {} into {}: {} ladder(s), {} variant(s){}",
                productId, props.qualifiedName(), plan.groups().size(),
                written.stream().mapToInt(v -> v.variantGids().size()).sum(),
                plan.productWide() ? " + the product" : "");
        return new DiscountResult(productId, Outcome.PUBLISHED, null, primary.basePrice(),
                primary.minimumQuantity(), primary.tiers(), props.qualifiedName(), plan.productWide(),
                List.copyOf(written), List.copyOf(warnings));
    }

    /**
     * Builds everything and writes nothing — the ladders, the grouping, the warnings and the exact
     * metafield values. Works on a product that is not in the store yet, which makes it the way to
     * check what a product would publish before importing it.
     */
    public Map<String, Object> preview(String productId) {
        Plan plan = plan(productId, false);
        List<Map<String, Object>> ladders = new ArrayList<>();
        for (Group group : plan.groups()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("supplierIds", group.supplierIds());
            entry.put("variantGids", group.variantGids());
            entry.put("basePrice", group.ladder().basePrice());
            entry.put("tiers", group.ladder().tiers());
            entry.put("value", parse(group.json()));
            ladders.add(entry);
        }

        QuantityLadder primary = plan.groups().isEmpty() ? plan.seed() : plan.groups().get(0).ladder();
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("productId", productId);
        preview.put("outcome", plan.groups().isEmpty() ? Outcome.NO_DISCOUNTS : Outcome.PREVIEWED);
        preview.put("reason", plan.reason());
        preview.put("metafield", props.qualifiedName());
        preview.put("metafieldType", props.type());
        preview.put("basePrice", primary == null ? null : primary.basePrice());
        preview.put("minimumQuantity", primary == null ? 1 : primary.minimumQuantity());
        preview.put("tiers", primary == null ? List.of() : primary.tiers());
        preview.put("productWide", plan.productWide());
        preview.put("ladders", ladders);
        preview.put("warnings", plan.warnings());
        // What the console shows under "View payload": the value itself when one covers the whole
        // product, else the value each group of ids gets.
        preview.put("payload", plan.groups().isEmpty() ? null
                : plan.productWide() ? parse(plan.groups().get(0).json()) : payloadByIds(plan));
        return preview;
    }

    // ---------------------------------------------------------------- planning

    /** The ids that share one ladder, the variants they map to, and the value those variants get. */
    private record Group(String primaryId, List<String> supplierIds, QuantityLadder ladder,
                         List<String> variantGids, String json) {
    }

    /** The decision and everything it needs, so preview and sync cannot diverge. */
    private record Plan(String productGid, List<Group> groups, boolean productWide,
                        QuantityLadder seed, String reason, List<String> warnings) {
    }

    private Plan plan(String productId, boolean storeRequired) {
        // Every id this Shopify product stands for: a grouped one covers several, each priced on its
        // own table.
        List<String> coveredIds = ordered(productId, shopifySync.supplierIdsFor(productId));
        Map<String, QuantityLadder> ladders = new LinkedHashMap<>();
        for (String id : coveredIds) {
            QuantityLadder ladder = ladderFor(id);
            if (ladder != null && ladder.hasDiscounts()) {
                ladders.put(id, ladder);
            }
        }
        if (ladders.isEmpty()) {
            QuantityLadder seed = ladderFor(productId);
            String reason = seed == null
                    ? "the supplier returned no price table"
                    : "the supplier's price table has no break cheaper than the first";
            return new Plan(null, List.of(), false, seed, reason, List.of());
        }

        List<String> warnings = new ArrayList<>();
        JsonNode storeProduct;
        try {
            storeProduct = shopifySync.storeProduct(productId);
        } catch (RuntimeException e) {
            if (storeRequired) {
                throw e;
            }
            storeProduct = null;
            warnings.add("not in the store yet (" + e.getMessage()
                    + "), so this preview cannot say which variants would carry the ladder");
        }
        Map<String, List<String>> variantsBySupplierId = variantsOf(storeProduct);

        // Ids producing the same JSON share a value; the rest need one each. Grouping on the value
        // itself is what matters: it is the payload, not the ladder behind it, that has to agree.
        Map<String, List<String>> idsByJson = new LinkedHashMap<>();
        for (Map.Entry<String, QuantityLadder> entry : ladders.entrySet()) {
            idsByJson.computeIfAbsent(json(entry.getValue()), k -> new ArrayList<>())
                    .add(entry.getKey());
        }

        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : idsByJson.entrySet()) {
            List<String> ids = entry.getValue();
            List<String> variantGids = new ArrayList<>();
            for (String id : ids) {
                for (String gid : variantsBySupplierId.getOrDefault(id.toUpperCase(Locale.ROOT), List.of())) {
                    if (!variantGids.contains(gid)) {
                        variantGids.add(gid);
                    }
                }
            }
            groups.add(new Group(ids.get(0), List.copyOf(ids), ladders.get(ids.get(0)),
                    List.copyOf(variantGids), entry.getKey()));
        }

        if (groups.size() > 1) {
            warnings.add("the supplier prices this product's parts on " + groups.size()
                    + " different ladders, so each one is written on its own variants: "
                    + groups.stream().map(Group::primaryId).toList());
        }
        QuantityLadder primary = groups.get(0).ladder();
        if (primary.hasMinimumQuantity()) {
            warnings.add("the supplier's table starts at " + primary.minimumQuantity()
                    + ", so this product has a minimum order quantity — the ladder says so, but "
                    + "enforcing it is the storefront's job");
        }
        // A product-wide value would claim this ladder for ids that do not have it, so it is written
        // only when one ladder covers every id the product stands for.
        boolean productWide = groups.size() == 1 && ladders.size() == coveredIds.size();
        if (groups.size() == 1 && !productWide) {
            warnings.add("only " + ladders.size() + " of the " + coveredIds.size()
                    + " ids this product covers have a quantity ladder, so nothing was written on "
                    + "the product itself — the value stays on the variants that earn it");
        }

        return new Plan(storeProduct == null ? null : storeProduct.path("id").asText(null),
                List.copyOf(groups), productWide, primary, null, List.copyOf(warnings));
    }

    /** The requested id first: its ladder is the one a single-value product publishes. */
    private static List<String> ordered(String productId, List<String> supplierIds) {
        List<String> ids = new ArrayList<>();
        ids.add(productId);
        for (String id : supplierIds) {
            if (!id.equalsIgnoreCase(productId)) {
                ids.add(id);
            }
        }
        return ids;
    }

    private QuantityLadder ladderFor(String supplierId) {
        try {
            return QuantityLadder.of(catalog.aggregate(supplierId).priceParts(), supplierId, pricingPolicy);
        } catch (RuntimeException e) {
            // One id the supplier no longer prices must not sink the rest of the product.
            log.warn("Could not price {} for its quantity ladder: {}", supplierId, e.getMessage());
            return null;
        }
    }

    private String json(QuantityLadder ladder) {
        return QuantityDiscountJson.of(ladder, syncProps.currency()).toJson(mapper);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not re-read the payload just built", e);
        }
    }

    private Map<String, Object> payloadByIds(Plan plan) {
        Map<String, Object> byIds = new LinkedHashMap<>();
        for (Group group : plan.groups()) {
            byIds.put(String.join(", ", group.supplierIds()), parse(group.json()));
        }
        return byIds;
    }

    /**
     * @return every variant gid per supplier id, from {@code custom.promo_standard_id}. Every one,
     * not the first: a supplier product sold in several colours or sizes puts its id on each of
     * them (CM330's eight colours all carry CM330), and keeping one gid per id wrote the ladder on
     * one variant and left seven priced wrong above the first break (2026-09-10).
     */
    private Map<String, List<String>> variantsOf(JsonNode storeProduct) {
        Map<String, List<String>> bySupplierId = new LinkedHashMap<>();
        if (storeProduct == null) {
            return bySupplierId;
        }
        for (JsonNode variant : storeProduct.path("variants").path("nodes")) {
            String supplierId = variant.path("psId").path("value").asText(null);
            if (supplierId == null || supplierId.isBlank()) {
                continue;
            }
            bySupplierId.computeIfAbsent(supplierId.trim().toUpperCase(Locale.ROOT), k -> new ArrayList<>())
                    .add(variant.path("id").asText());
        }
        return bySupplierId;
    }

    /**
     * Shopify's answer when an app writes into a namespace it does not own — verified on the live
     * store, publishing CM373BS into {@code app--400283500545}: {@code "Access to this namespace and
     * key on Metafields for this resource type is not allowed."} It is a userError, not a transport
     * failure, so without this it would surface as a wall of GraphQL noise on an import that
     * otherwise worked.
     */
    private static boolean refusedNamespace(RuntimeException e) {
        String message = e.getMessage();
        return message != null && message.contains("Access to this namespace");
    }

    private DiscountResult refused(String productId, QuantityLadder primary, List<String> warnings,
                                   RuntimeException cause) {
        log.warn("Shopify refused the quantity discounts for {} in {}: {}", productId,
                props.qualifiedName(), cause.getMessage());
        List<String> all = new ArrayList<>(warnings);
        all.add(cause.getMessage());
        return new DiscountResult(productId, Outcome.NOT_WRITTEN,
                "Shopify refuses writes to " + props.qualifiedName() + ": the app-- prefix reserves "
                        + "that namespace for the app that owns it. Either its owner grants this app "
                        + "access to the definition, or point discounts.namespace/key at a "
                        + "merchant-owned metafield the storefront app can read.",
                primary.basePrice(), primary.minimumQuantity(), primary.tiers(),
                props.qualifiedName(), false, List.of(), List.copyOf(all));
    }

    private DiscountResult noDiscounts(String productId, Plan plan) {
        return new DiscountResult(productId, Outcome.NO_DISCOUNTS, plan.reason(),
                plan.seed() == null ? null : plan.seed().basePrice(),
                plan.seed() == null ? 1 : plan.seed().minimumQuantity(), List.of(),
                props.qualifiedName(), false, List.of(), plan.warnings());
    }

    private DiscountResult skipped(String productId, String reason) {
        return new DiscountResult(productId, Outcome.SKIPPED, reason, null, 1, List.of(),
                props.qualifiedName(), false, List.of(), List.of());
    }
}
