package com.trophy.promostandards.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps a {@link SupplierProduct} onto the variables for the {@code productSet} mutation: a
 * deterministic handle, Color/Size options, per-variant SKU/price/option values, product media, and
 * identity metafields. Retail prices come from {@link PricingPolicy}; this keeps the mapper a pure,
 * unit-testable transformation.
 */
@Component
public class ShopifyProductMapper {

    /** Tag applied to every imported product so the app can discover what it owns. */
    static final String OWNER_TAG = "promostandards";
    static final String METAFIELD_NAMESPACE = "custom";
    /**
     * @deprecated the migration's key. The app writes {@link #MF_VENDOR} now; this one is only ever
     * read, as the fallback for a product that has not been synced since.
     */
    @Deprecated
    static final String MF_SUPPLIER = "ps_supplier";
    static final String MF_PRODUCT_ID = "ps_product_id";
    /** List of every supplier id a product covers (migration N:1 grouping; canonical included). */
    static final String MF_PRODUCT_IDS = "ps_product_ids";
    static final String MF_PRICE_BREAKS = "ps_price_breaks";
    /**
     * Provenance: who created the product. Immutable — never overwritten once set.
     *
     * @deprecated the migration's key; {@link #MF_SOURCE_NEW} is what the app writes. Read as a
     * fallback so a product carries provenance from its first sync, not only after it.
     */
    @Deprecated
    static final String MF_SOURCE = "ps_source";
    static final String SOURCE_APP = "app";
    static final String SOURCE_MIGRATION = "migration";
    /**
     * Mutable companion to {@link #MF_SOURCE}: stamped on every successful sync.
     *
     * @deprecated the migration's key; the app writes {@link #MF_LAST_SYNC_AT_NEW}.
     */
    @Deprecated
    static final String MF_LAST_SYNC_AT = "ps_last_sync_at";

    /**
     * The bookkeeping the sync owns, in its own namespace — the {@code custom.ps_*} equivalents came
     * from the one-shot migration and are read-only fallbacks from here on. Splitting them apart is
     * what makes "what the migration said" and "what the sync knows" two different questions, which
     * matters while both are true of the same product.
     */
    static final String MF_VENDOR = "vendor";
    static final String MF_SOURCE_NEW = "source";
    static final String MF_LAST_SYNC_AT_NEW = "last_sync_at";
    /** Variant-level metafield carrying the supplier product id (e.g. {@code C0611}). */
    static final String MF_VARIANT_PROMO_STANDARD_ID = "promo_standard_id";

    /**
     * Namespace for facts this sync publishes for the storefront to read, as opposed to the
     * {@code custom} identity metafields the app matches on.
     */
    static final String SYNC_NAMESPACE = "trophy_sync";

    /**
     * The supplier's colour name, verbatim, per variant. It is a <b>name</b> and nothing else:
     * PromoStandards models {@code Color} with {@code colorName}, {@code hex} and
     * {@code approximatePms}, but PaceSetter answers "N/A" to all three in Product Data — the real
     * colour only ever arrives as {@code attributeColor} on an Inventory row (verified live,
     * 2026-09-04). The theme gets the string; any swatch has to be mapped from it downstream.
     *
     * <p>Not the option value: that one is disambiguated when a family reuses a colour across parts
     * ("Dark Brown (BL)" — see {@link VariantOptions}), while this is what the supplier said.
     */
    static final String MF_VARIANT_COLOR = "color";

    /**
     * The supplier's own part id, verbatim — {@code CM2541LB}. This is the join back to PaceSetter,
     * and it exists because the SKU stopped being one: a migrated product's variants are numbered
     * from the legacy catalogue ({@code PS1298-LB}), which means nothing upstream. Identity for
     * matching therefore lives in metafields, and the SKU is free to be whatever the shop wants.
     */
    static final String MF_VARIANT_VENDOR_SKU = "vendor_sku";

    /** @return the per-variant vendor SKU metafield, or null when the supplier named no part. */
    static Map<String, Object> vendorSkuMetafield(String supplierPartId) {
        return supplierPartId == null || supplierPartId.isBlank() ? null
                : Map.of("namespace", SYNC_NAMESPACE, "key", MF_VARIANT_VENDOR_SKU,
                         "type", "single_line_text_field", "value", supplierPartId);
    }

    /** @return the per-variant colour metafield, or null when the supplier named no colour. */
    static Map<String, Object> colorMetafield(String supplierColor) {
        return supplierColor == null || supplierColor.isBlank() ? null
                : Map.of("namespace", SYNC_NAMESPACE, "key", MF_VARIANT_COLOR,
                         "type", "single_line_text_field", "value", supplierColor);
    }
    private static final String COLOR = VariantOptions.COLOR;
    private static final String SIZE = VariantOptions.SIZE;

    private final SyncProperties props;
    private final PricingPolicy pricingPolicy;
    private final ObjectMapper objectMapper;
    private final ShopifyProperties shopify;

    public ShopifyProductMapper(SyncProperties props, PricingPolicy pricingPolicy, ObjectMapper objectMapper,
                                ShopifyProperties shopify) {
        this.props = props;
        this.pricingPolicy = pricingPolicy;
        this.objectMapper = objectMapper;
        this.shopify = shopify;
    }

    /** The deterministic handle used to find/upsert a product: {@code ps-<supplier>-<productId>}. */
    public String handle(String productId) {
        return slug("ps-" + props.supplierCode() + "-" + productId);
    }

    /** Builds {@code {input, synchronous}} variables for {@link ShopifyGraphQL#PRODUCT_SET}. */
    public Map<String, Object> productSetVariables(SupplierProduct product, String existingProductGid) {
        return productSetVariables(product, existingProductGid, List.of());
    }

    /**
     * Builds {@code {input, synchronous}} variables, additionally stamping per-import metafields the
     * user picked in the UI ({@code extraMetafields}); these override any same-key {@code sync.metafields}.
     */
    public Map<String, Object> productSetVariables(SupplierProduct product, String existingProductGid,
                                                   List<SyncProperties.Metafield> extraMetafields) {
        return Map.of("input", buildInput(product, existingProductGid, extraMetafields), "synchronous", true);
    }

    Map<String, Object> buildInput(SupplierProduct product, String existingProductGid) {
        return buildInput(product, existingProductGid, List.of());
    }

    Map<String, Object> buildInput(SupplierProduct product, String existingProductGid,
                                   List<SyncProperties.Metafield> extraMetafields) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("handle", handle(product.productId()));
        if (existingProductGid != null && !existingProductGid.isBlank()) {
            input.put("id", existingProductGid);
        }
        input.put("title", product.title());
        if (product.descriptionHtml() != null) {
            input.put("descriptionHtml", product.descriptionHtml());
        }
        if (product.vendor() != null) {
            input.put("vendor", product.vendor());
        }
        if (product.productType() != null) {
            input.put("productType", product.productType());
        }
        input.put("status", "ACTIVE");
        input.put("tags", tags(product));
        input.put("productOptions", productOptions(product));
        input.put("variants", variants(product));

        List<Map<String, Object>> files = files(product);
        if (!files.isEmpty()) {
            input.put("files", files);
        }
        input.put("metafields", metafields(product, extraMetafields));
        return input;
    }

    private List<Map<String, Object>> metafields(SupplierProduct product,
                                                 List<SyncProperties.Metafield> extraMetafields) {
        List<Map<String, Object>> metafields = new ArrayList<>();
        metafields.add(syncMetafield(MF_VENDOR, props.supplierCode()));
        metafields.add(metafield(MF_PRODUCT_ID, product.productId()));
        // Shopify variants carry a single price, so preserve the full quantity price-break matrix here.
        if (product.priceParts() != null && !product.priceParts().isEmpty()) {
            try {
                metafields.add(jsonMetafield(MF_PRICE_BREAKS, objectMapper.writeValueAsString(product.priceParts())));
            } catch (JsonProcessingException e) {
                // non-fatal: skip the metafield rather than fail the whole import
            }
        }
        // Custom metafields: config defaults (sync.metafields) then per-import UI selections, the
        // latter overriding any same namespace+key. Both carry a constant value or a supplier source.
        Map<String, Map<String, Object>> custom = new LinkedHashMap<>();
        for (SyncProperties.Metafield spec : concat(props.metafields(), extraMetafields)) {
            String value = resolveMetafieldValue(spec, product);
            if (value != null && !value.isBlank()) {
                Map<String, Object> mf = customMetafield(spec, value);
                custom.put(mf.get("namespace") + "|" + mf.get("key"), mf);
            }
        }
        metafields.addAll(custom.values());
        return metafields;
    }

    private static List<SyncProperties.Metafield> concat(List<SyncProperties.Metafield> configured,
                                                         List<SyncProperties.Metafield> extra) {
        List<SyncProperties.Metafield> all = new ArrayList<>();
        if (configured != null) {
            all.addAll(configured);
        }
        if (extra != null) {
            all.addAll(extra);
        }
        return all;
    }

    private String resolveMetafieldValue(SyncProperties.Metafield spec, SupplierProduct product) {
        if (spec.value() != null) {
            return spec.value();
        }
        if (spec.source() == null) {
            return null;
        }
        return switch (spec.source()) {
            case "title" -> product.title();
            case "description" -> product.descriptionHtml();
            case "vendor" -> product.vendor();
            case "productType" -> product.productType();
            case "productId" -> product.productId();
            case "supplierCode" -> props.supplierCode();
            case "tags" -> tagsValue(spec.type(), product.tags());
            default -> null;
        };
    }

    private String tagsValue(String type, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        if (type != null && type.startsWith("list.")) {
            try {
                return objectMapper.writeValueAsString(tags);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
        return String.join(", ", tags);
    }

    private Map<String, Object> customMetafield(SyncProperties.Metafield spec, String value) {
        return Map.of(
                "namespace", spec.namespace() == null || spec.namespace().isBlank() ? METAFIELD_NAMESPACE : spec.namespace(),
                "key", spec.key(),
                "type", spec.type() == null || spec.type().isBlank() ? "single_line_text_field" : spec.type(),
                "value", value);
    }

    private List<String> tags(SupplierProduct product) {
        Set<String> tags = new LinkedHashSet<>();
        tags.add(OWNER_TAG);
        if (product.tags() != null) {
            tags.addAll(product.tags());
        }
        return new ArrayList<>(tags);
    }

    private boolean hasSize(SupplierProduct product) {
        return product.variants().stream().anyMatch(v -> v.size() != null && !v.size().isBlank());
    }

    private List<Map<String, Object>> productOptions(SupplierProduct product) {
        List<Map<String, Object>> options = new ArrayList<>();
        options.add(optionDef(COLOR, 1, distinct(VariantOptions.colorLabels(product.variants()))));
        if (hasSize(product)) {
            options.add(optionDef(SIZE, 2, distinct(product, v -> defaultSize(v.size()))));
        }
        return options;
    }

    private Map<String, Object> optionDef(String name, int position, List<String> values) {
        List<Map<String, String>> optionValues = values.stream().map(v -> Map.of("name", v)).toList();
        return Map.of("name", name, "position", position, "values", optionValues);
    }

    private List<Map<String, Object>> variants(SupplierProduct product) {
        boolean hasSize = hasSize(product);
        // Two parts of a family can share a colour name; the label carries the part id when they do,
        // because Shopify rejects two variants claiming the same option combination.
        List<String> colorLabels = VariantOptions.colorLabels(product.variants());
        List<Map<String, Object>> variants = new ArrayList<>();
        for (int i = 0; i < product.variants().size(); i++) {
            Variant v = product.variants().get(i);
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("sku", v.sku());
            BigDecimal price = pricingPolicy.retailPrice(v.supplierNet(), v.listPrice());
            if (price != null) {
                variant.put("price", price.toPlainString());
            }
            List<Map<String, String>> optionValues = new ArrayList<>();
            optionValues.add(Map.of("optionName", COLOR, "name", colorLabels.get(i)));
            if (hasSize) {
                optionValues.add(Map.of("optionName", SIZE, "name", defaultSize(v.size())));
            }
            variant.put("optionValues", optionValues);
            List<Map<String, Object>> variantMetafields = new ArrayList<>();
            variantMetafields.add(Map.of(
                    "namespace", METAFIELD_NAMESPACE,
                    "key", MF_VARIANT_PROMO_STANDARD_ID,
                    "type", "single_line_text_field",
                    "value", product.productId()));
            Map<String, Object> color = colorMetafield(v.color());
            if (color != null) {
                variantMetafields.add(color);
            }
            Map<String, Object> vendorSku = vendorSkuMetafield(v.supplierPartId());
            if (vendorSku != null) {
                variantMetafields.add(vendorSku);
            }
            variant.put("metafields", variantMetafields);
            variant.put("inventoryItem", Map.of("tracked", true));
            // The variant's own photo, so the storefront swaps it when a colour is picked. It has to
            // be one of the product's files (Shopify rejects a variant file that is not), which it is:
            // the gallery is the union of every variant's images.
            if (!v.imageUrls().isEmpty()) {
                variant.put("file", Map.of("originalSource", v.imageUrls().get(0),
                        "contentType", "IMAGE"));
            }
            // Set on-hand at import time (activates the item at the location + sets quantity atomically).
            String locationId = shopify.locationId();
            if (locationId != null && !locationId.isBlank() && v.onHand() != null) {
                variant.put("inventoryQuantities", List.of(Map.of(
                        "locationId", locationId, "name", "available", "quantity", v.onHand())));
            }
            variants.add(variant);
        }
        return variants;
    }

    private List<Map<String, Object>> files(SupplierProduct product) {
        List<Map<String, Object>> files = new ArrayList<>();
        for (String url : product.imageUrls()) {
            files.add(Map.of("originalSource", url, "contentType", "IMAGE"));
        }
        return files;
    }

    /** A metafield in the sync's own namespace ({@code trophy_sync}). */
    private static Map<String, Object> syncMetafield(String key, String value) {
        return Map.of("namespace", SYNC_NAMESPACE, "key", key, "type", "single_line_text_field",
                "value", value == null ? "" : value);
    }

    private Map<String, Object> metafield(String key, String value) {
        return Map.of(
                "namespace", METAFIELD_NAMESPACE,
                "key", key,
                "type", "single_line_text_field",
                "value", value == null ? "" : value);
    }

    private Map<String, Object> jsonMetafield(String key, String json) {
        return Map.of("namespace", METAFIELD_NAMESPACE, "key", key, "type", "json", "value", json);
    }

    private List<String> distinct(SupplierProduct product, java.util.function.Function<Variant, String> f) {
        return distinct(product.variants().stream().map(f).toList());
    }

    private List<String> distinct(List<String> raw) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : raw) {
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private static String defaultSize(String size) {
        return VariantOptions.size(size);
    }

    private static String slug(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-+)|(-+$)", "");
    }
}
