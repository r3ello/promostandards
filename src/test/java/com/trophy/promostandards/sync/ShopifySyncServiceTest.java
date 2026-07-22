package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyHttp;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.shopify.ShopifyTokenService;
import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopifySyncServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> operations = new ArrayList<>();
    private Map<?, ?> productSetVariables;

    /** Routes by the GraphQL operation name embedded in the query string. */
    private ShopifyHttp routingHttp() {
        return (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            String response;
            if (query.contains("ProductByHandle")) {
                operations.add("ProductByHandle");
                response = "{\"data\":{\"products\":{\"nodes\":[]}}}"; // not yet imported -> create
            } else if (query.contains("MetaobjectByHandle")) {
                operations.add("MetaobjectByHandle");
                response = "{\"data\":{\"metaobjectByHandle\":{\"id\":\"gid://shopify/Metaobject/77\","
                        + "\"type\":\"promo_standard_supplier\",\"handle\":\"pace-setter\"}}}";
            } else if (query.contains("ProductSet")) {
                operations.add("ProductSet");
                productSetVariables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
                response = """
                        {"data":{"productSet":{"product":{
                          "id":"gid://shopify/Product/100",
                          "handle":"ps-pacesetter-sample-001",
                          "variants":{"nodes":[
                            {"id":"gid://shopify/ProductVariant/1","sku":"SAMPLE-001-RED-S","inventoryItem":{"id":"gid://shopify/InventoryItem/11"}},
                            {"id":"gid://shopify/ProductVariant/2","sku":"SAMPLE-001-RED-M","inventoryItem":{"id":"gid://shopify/InventoryItem/12"}}
                          ]}
                        },"userErrors":[]}}}""";
            } else if (query.contains("InventorySet")) {
                operations.add("InventorySet");
                response = "{\"data\":{\"inventorySetQuantities\":{\"inventoryAdjustmentGroup\":{\"createdAt\":\"now\"},\"userErrors\":[]}}}";
            } else {
                throw new IllegalStateException("unexpected query: " + query);
            }
            try {
                return MAPPER.readTree(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    private ShopifySyncService service(ShopifyHttp http) {
        ShopifyProperties shopify = new ShopifyProperties("shop.myshopify.com", "id", "secret",
                "whsec", "2026-04", "gid://shopify/Location/1");
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        ShopifyGraphQLClient gql = new ShopifyGraphQLClient(http, tokens, shopify);

        SyncProperties syncProps = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-"), List.of(),
                new SyncProperties.SupplierMetaobject("promo_standard_supplier", "pace-setter"));
        PricingPolicy policy = new PricingPolicy(syncProps);
        ShopifyProductMapper mapper = new ShopifyProductMapper(syncProps, policy, new ObjectMapper(), shopify);

        CatalogService catalog = mock(CatalogService.class);
        when(catalog.aggregate("SAMPLE-001")).thenReturn(sample());

        return new ShopifySyncService(gql, catalog, mapper, policy, shopify, syncProps);
    }

    private static SupplierProduct sample() {
        return new SupplierProduct("SAMPLE-001", "Sample Polo", "<p>x</p>", "Trophy Apparel", "Polos",
                List.of("Polos"),
                List.of(
                        new Variant("SAMPLE-001-RED", "Red", "S", "SAMPLE-001-RED-S",
                                new BigDecimal("9.50"), new BigDecimal("12.00"), 1200, List.of()),
                        new Variant("SAMPLE-001-RED", "Red", "M", "SAMPLE-001-RED-M",
                                new BigDecimal("9.50"), new BigDecimal("12.00"), 350, List.of())),
                List.of(), List.of());
    }

    @Test
    void importCreatesProductThenPushesInventory() {
        ShopifySyncService service = service(routingHttp());

        SyncResult result = service.importProduct("SAMPLE-001");

        assertThat(result.shopifyProductId()).isEqualTo("gid://shopify/Product/100");
        assertThat(result.handle()).isEqualTo("ps-pacesetter-sample-001");
        assertThat(result.updated()).isFalse();
        assertThat(result.variantCount()).isEqualTo(2);
        assertThat(result.inventoryUpdated()).isEqualTo(2); // both variants carry on-hand

        // Inventory is set within productSet now, so no separate InventorySet call on import.
        assertThat(operations).containsExactly("ProductByHandle", "MetaobjectByHandle", "ProductSet");

        // The supplier metaobject reference is stamped as a product metafield (resolved GID)...
        Map<?, ?> input = (Map<?, ?>) productSetVariables.get("input");
        List<?> metafields = (List<?>) input.get("metafields");
        assertThat(metafields).anySatisfy(m -> {
            Map<?, ?> mf = (Map<?, ?>) m;
            assertThat(mf.get("key")).isEqualTo("promo_standard_supplier");
            assertThat(mf.get("type")).isEqualTo("metaobject_reference");
            assertThat(mf.get("value")).isEqualTo("gid://shopify/Metaobject/77");
        });
        // ...and every variant carries the supplier product id.
        List<?> variants = (List<?>) input.get("variants");
        assertThat(variants).isNotEmpty().allSatisfy(v -> {
            List<?> variantMetafields = (List<?>) ((Map<?, ?>) v).get("metafields");
            assertThat(variantMetafields).anySatisfy(m -> {
                Map<?, ?> mf = (Map<?, ?>) m;
                assertThat(mf.get("key")).isEqualTo("promo_standard_id");
                assertThat(mf.get("value")).isEqualTo("SAMPLE-001");
            });
        });
    }

    @Test
    void surfacesProductSetUserErrors() {
        ShopifyHttp http = (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            String response = query.contains("ProductByHandle")
                    ? "{\"data\":{\"products\":{\"nodes\":[]}}}"
                    : "{\"data\":{\"productSet\":{\"product\":null,\"userErrors\":[{\"field\":\"handle\",\"message\":\"taken\"}]}}}";
            try {
                return MAPPER.readTree(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifySyncService service = service(http);

        try {
            service.importProduct("SAMPLE-001");
            assertThat(false).as("expected ShopifySyncException").isTrue();
        } catch (ShopifySyncException expected) {
            assertThat(expected).hasMessageContaining("productSet userErrors");
        }
    }
}
