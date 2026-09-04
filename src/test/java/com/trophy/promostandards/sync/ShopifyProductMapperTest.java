package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShopifyProductMapperTest {

    private static final ShopifyProperties SHOPIFY = new ShopifyProperties(
            "shop.myshopify.com", "id", "secret", "whsec", "2026-04", "gid://shopify/Location/1");

    private static ShopifyProductMapper mapper() {
        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        return new ShopifyProductMapper(props, new PricingPolicy(props), new ObjectMapper(), SHOPIFY);
    }

    private static List<Configuration.PartPrice> priceParts() {
        return List.of(new Configuration.PartPrice("SAMPLE-001-RED", "Red colorway", List.of(
                new Configuration.PriceBreak(12, new BigDecimal("10.00"), new BigDecimal("12.00"), "EA"),
                new Configuration.PriceBreak(48, new BigDecimal("8.50"), new BigDecimal("12.00"), "EA"))));
    }

    private static SupplierProduct sample() {
        return new SupplierProduct("SAMPLE-001", "Sample Polo", "<p>Soft polo</p>", "Trophy Apparel",
                "Polos", List.of("Apparel", "Polos"),
                List.of(
                        new Variant("SAMPLE-001-RED", "Red", "S", "SAMPLE-001-RED-S",
                                new BigDecimal("10.00"), new BigDecimal("12.00"), 1200,
                                List.of("https://cdn.example.com/red.jpg")),
                        new Variant("SAMPLE-001-RED", "Red", "M", "SAMPLE-001-RED-M",
                                new BigDecimal("10.00"), new BigDecimal("12.00"), 350,
                                List.of("https://cdn.example.com/red.jpg")),
                        new Variant("SAMPLE-001-BLU", "Blue", "S", "SAMPLE-001-BLU-S",
                                new BigDecimal("10.00"), new BigDecimal("12.00"), 0,
                                List.of("https://cdn.example.com/blue.jpg"))),
                List.of("https://cdn.example.com/red.jpg", "https://cdn.example.com/blue.jpg"),
                priceParts(), List.of());
    }

    private static SupplierProduct singleNullColorVariant() {
        return new SupplierProduct("C073A", "Walnut Plaque", null, null, null, List.of(),
                List.of(new Variant("C073A", null, null, "C073A",
                        new BigDecimal("125.00"), new BigDecimal("125.00"), null, List.of())),
                List.of(), List.of(), List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsHandleOptionsVariantsAndMetafields() {
        ShopifyProductMapper mapper = mapper();
        Map<String, Object> vars = mapper.productSetVariables(sample(), null);

        assertThat(vars).containsEntry("synchronous", true);
        Map<String, Object> input = (Map<String, Object>) vars.get("input");

        assertThat(input.get("handle")).isEqualTo("ps-pacesetter-sample-001");
        assertThat(input).doesNotContainKey("id"); // create, not update
        assertThat(input.get("status")).isEqualTo("ACTIVE");
        assertThat((List<String>) input.get("tags")).contains("promostandards", "Apparel", "Polos");

        List<Map<String, Object>> options = (List<Map<String, Object>>) input.get("productOptions");
        assertThat(options).extracting(o -> o.get("name")).containsExactly("Color", "Size");

        List<Map<String, Object>> variants = (List<Map<String, Object>>) input.get("variants");
        assertThat(variants).hasSize(3);
        assertThat(variants.get(0).get("sku")).isEqualTo("SAMPLE-001-RED-S");
        assertThat(variants.get(0).get("price")).isEqualTo("14.00"); // 10.00 * 1.40
        assertThat(variants.get(0).get("inventoryItem")).isEqualTo(Map.of("tracked", true));
        // Inventory set within productSet at the configured location.
        assertThat((List<Map<String, Object>>) variants.get(0).get("inventoryQuantities"))
                .containsExactly(Map.of("locationId", "gid://shopify/Location/1", "name", "available", "quantity", 1200));
        // Every variant carries the supplier product id as a variant-level metafield.
        for (Map<String, Object> variant : variants) {
            assertThat((List<Map<String, Object>>) variant.get("metafields")).containsExactly(Map.of(
                    "namespace", "custom", "key", "promo_standard_id",
                    "type", "single_line_text_field", "value", "SAMPLE-001"));
        }

        List<Map<String, Object>> metafields = (List<Map<String, Object>>) input.get("metafields");
        assertThat(metafields).anySatisfy(m -> {
            assertThat(m.get("key")).isEqualTo("ps_product_id");
            assertThat(m.get("value")).isEqualTo("SAMPLE-001");
        });
        // The full price-break matrix is preserved as a json metafield.
        assertThat(metafields).anySatisfy(m -> {
            assertThat(m.get("key")).isEqualTo("ps_price_breaks");
            assertThat(m.get("type")).isEqualTo("json");
            assertThat((String) m.get("value")).contains("SAMPLE-001-RED").contains("minQuantity");
        });

        List<Map<String, Object>> files = (List<Map<String, Object>>) input.get("files");
        assertThat(files).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesConfiguredMetafields() {
        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false),
                List.of(
                        new SyncProperties.Metafield("custom", "country_of_origin", "single_line_text_field", "China", null),
                        new SyncProperties.Metafield("custom", "supplier_brand", "single_line_text_field", null, "vendor")),
                null);
        ShopifyProductMapper mapper = new ShopifyProductMapper(props, new PricingPolicy(props), new ObjectMapper(), SHOPIFY);

        Map<String, Object> input = (Map<String, Object>) mapper.productSetVariables(sample(), null).get("input");
        List<Map<String, Object>> metafields = (List<Map<String, Object>>) input.get("metafields");

        assertThat(metafields).anySatisfy(m -> {
            assertThat(m.get("key")).isEqualTo("country_of_origin");
            assertThat(m.get("value")).isEqualTo("China"); // constant
        });
        assertThat(metafields).anySatisfy(m -> {
            assertThat(m.get("key")).isEqualTo("supplier_brand");
            assertThat(m.get("value")).isEqualTo("Trophy Apparel"); // copied from vendor
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesPerImportSelectedMetafields() {
        // sync.metafields defines a default for custom.season; the per-import selection overrides it.
        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false),
                List.of(new SyncProperties.Metafield("custom", "season", "single_line_text_field", "Winter", null)),
                null);
        ShopifyProductMapper mapper = new ShopifyProductMapper(props, new PricingPolicy(props), new ObjectMapper(), SHOPIFY);

        List<SyncProperties.Metafield> extra = List.of(
                new SyncProperties.Metafield("custom", "season", "single_line_text_field", "2026", null),
                new SyncProperties.Metafield("custom", "supplier_brand", "single_line_text_field", null, "vendor"));

        Map<String, Object> input = (Map<String, Object>) mapper.productSetVariables(sample(), null, extra).get("input");
        List<Map<String, Object>> metafields = (List<Map<String, Object>>) input.get("metafields");

        // The per-import value wins over the configured default, and only one custom.season is emitted.
        assertThat(metafields).filteredOn(m -> "season".equals(m.get("key"))).singleElement()
                .satisfies(m -> assertThat(m.get("value")).isEqualTo("2026"));
        // A source-mapped selection copies the supplier field.
        assertThat(metafields).anySatisfy(m -> {
            assertThat(m.get("key")).isEqualTo("supplier_brand");
            assertThat(m.get("value")).isEqualTo("Trophy Apparel"); // copied from vendor
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void handlesNullColorWithoutFailing() {
        Map<String, Object> vars = mapper().productSetVariables(singleNullColorVariant(), null);
        Map<String, Object> input = (Map<String, Object>) vars.get("input");

        List<Map<String, Object>> options = (List<Map<String, Object>>) input.get("productOptions");
        assertThat(options).extracting(o -> o.get("name")).containsExactly("Color"); // no Size option
        Map<String, Object> colorOption = options.get(0);
        assertThat((List<Map<String, String>>) colorOption.get("values")).containsExactly(Map.of("name", "Default"));

        List<Map<String, Object>> variants = (List<Map<String, Object>>) input.get("variants");
        assertThat(variants).hasSize(1);
        assertThat((List<Map<String, String>>) variants.get(0).get("optionValues"))
                .containsExactly(Map.of("optionName", "Color", "name", "Default"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void includesProductIdWhenUpdating() {
        Map<String, Object> vars = mapper().productSetVariables(sample(), "gid://shopify/Product/42");
        Map<String, Object> input = (Map<String, Object>) vars.get("input");
        assertThat(input.get("id")).isEqualTo("gid://shopify/Product/42");
    }
}
