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
    static final String MF_SUPPLIER = "ps_supplier";
    static final String MF_PRODUCT_ID = "ps_product_id";
    static final String MF_PRICE_BREAKS = "ps_price_breaks";
    /** Variant-level metafield carrying the supplier product id (e.g. {@code C0611}). */
    static final String MF_VARIANT_PROMO_STANDARD_ID = "promo_standard_id";
    private static final String COLOR = "Color";
    private static final String SIZE = "Size";
    private static final String DEFAULT_COLOR = "Default";

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
        metafields.add(metafield(MF_SUPPLIER, props.supplierCode()));
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
        options.add(optionDef(COLOR, 1, distinct(product, v -> defaultColor(v.color()))));
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
        List<Map<String, Object>> variants = new ArrayList<>();
        for (Variant v : product.variants()) {
            Map<String, Object> variant = new LinkedHashMap<>();
            variant.put("sku", v.sku());
            BigDecimal price = pricingPolicy.retailPrice(v.supplierNet(), v.listPrice());
            if (price != null) {
                variant.put("price", price.toPlainString());
            }
            List<Map<String, String>> optionValues = new ArrayList<>();
            optionValues.add(Map.of("optionName", COLOR, "name", defaultColor(v.color())));
            if (hasSize) {
                optionValues.add(Map.of("optionName", SIZE, "name", defaultSize(v.size())));
            }
            variant.put("optionValues", optionValues);
            variant.put("metafields", List.of(Map.of(
                    "namespace", METAFIELD_NAMESPACE,
                    "key", MF_VARIANT_PROMO_STANDARD_ID,
                    "type", "single_line_text_field",
                    "value", product.productId())));
            variant.put("inventoryItem", Map.of("tracked", true));
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
        Set<String> values = new LinkedHashSet<>();
        for (Variant v : product.variants()) {
            String value = f.apply(v);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private static String defaultColor(String color) {
        return color == null || color.isBlank() ? DEFAULT_COLOR : color;
    }

    private static String defaultSize(String size) {
        return size == null || size.isBlank() ? "One Size" : size;
    }

    private static String slug(String raw) {
        String s = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        return s.replaceAll("(^-+)|(-+$)", "");
    }
}
