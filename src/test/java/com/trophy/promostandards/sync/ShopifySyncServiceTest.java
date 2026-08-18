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
import com.trophy.promostandards.shopify.ShopifyRetryProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopifySyncServiceTest {

    /** No persistence in these tests: every push happens, exactly as before the database existed. */
    private com.trophy.promostandards.db.SyncStateStore syncState = null;

    /** Retry fast in tests: the throttling backoff is behaviour, not something to wait out. */
    private static final ShopifyRetryProperties TEST_RETRY =
            new ShopifyRetryProperties(3, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> operations = new ArrayList<>();
    private Map<?, ?> productSetVariables;
    private Map<?, ?> metafieldsSetVariables;

    /** Routes by the GraphQL operation name embedded in the query string. */
    private ShopifyHttp routingHttp() {
        return (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            String response;
            if (query.contains("ProductByHandle")) {
                operations.add("ProductByHandle");
                response = "{\"data\":{\"products\":{\"nodes\":[]}}}"; // not yet imported -> create
            } else if (query.contains("ImportedProducts")) {
                operations.add("ImportedProducts");
                response = "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[]}}}";
            } else if (query.contains("MetafieldsSet")) {
                operations.add("MetafieldsSet");
                metafieldsSetVariables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
                response = "{\"data\":{\"metafieldsSet\":{\"metafields\":[],\"userErrors\":[]}}}";
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
        ShopifyGraphQLClient gql = new ShopifyGraphQLClient(http, tokens, shopify, TEST_RETRY);

        SyncProperties syncProps = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(),
                new SyncProperties.SupplierMetaobject("promo_standard_supplier", "pace-setter"));
        PricingPolicy policy = new PricingPolicy(syncProps);
        ShopifyProductMapper mapper = new ShopifyProductMapper(syncProps, policy, new ObjectMapper(), shopify);

        CatalogService catalog = mock(CatalogService.class);
        when(catalog.aggregate("SAMPLE-001")).thenReturn(sample());

        return new ShopifySyncService(gql, catalog, mapper, policy, shopify, syncProps, new ObjectMapper(),
                CatalogTestSupport.providerOf(syncState));
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

        // Inventory is set within productSet now, so no separate InventorySet call on import. The
        // handle miss triggers one migrated-product index lookup (ImportedProducts) before creating,
        // and the app stamps ps_source/ps_last_sync_at (MetafieldsSet) after.
        assertThat(operations).containsExactly("ProductByHandle", "ImportedProducts",
                "MetaobjectByHandle", "ProductSet", "MetafieldsSet");

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

    /**
     * Store state for the migrated-product tests: nothing under the app handle, one migration-created
     * product ({@code p-8123-sample-polo}) whose {@code ps_product_ids} covers SAMPLE-001 (metafield
     * values deliberately lower-cased to prove case-insensitive resolution) plus a sibling CM777.
     */
    private ShopifyHttp migratedStoreHttp() {
        return (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            Map<?, ?> variables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
            String response;
            if (query.contains("ProductByHandle")) {
                operations.add("ProductByHandle");
                response = String.valueOf(variables.get("query")).contains("p-8123-sample-polo") ? """
                        {"data":{"products":{"nodes":[{
                          "id":"gid://shopify/Product/900",
                          "handle":"p-8123-sample-polo",
                          "variants":{"nodes":[
                            {"id":"gid://shopify/ProductVariant/91","sku":"SAMPLE-001-RED-S","inventoryItem":{"id":"gid://shopify/InventoryItem/191"}},
                            {"id":"gid://shopify/ProductVariant/92","sku":"SAMPLE-001-RED-M","inventoryItem":{"id":"gid://shopify/InventoryItem/192"}},
                            {"id":"gid://shopify/ProductVariant/93","sku":"PSLEGACY-XL","inventoryItem":{"id":"gid://shopify/InventoryItem/193"}}
                          ]}
                        }]}}}"""
                        : "{\"data\":{\"products\":{\"nodes\":[]}}}";
            } else if (query.contains("ImportedProducts")) {
                operations.add("ImportedProducts");
                response = """
                        {"data":{"products":{"pageInfo":{"hasNextPage":false},"nodes":[{
                          "id":"gid://shopify/Product/900",
                          "handle":"p-8123-sample-polo",
                          "psId":{"value":"sample-001"},
                          "psIds":{"value":"[\\"sample-001\\",\\"CM777\\"]"},
                          "psSource":{"value":"migration"}
                        }]}}}""";
            } else if (query.contains("InventorySet")) {
                operations.add("InventorySet");
                response = "{\"data\":{\"inventorySetQuantities\":{\"inventoryAdjustmentGroup\":{\"createdAt\":\"now\"},\"userErrors\":[]}}}";
            } else if (query.contains("VariantsUpdate")) {
                operations.add("VariantsUpdate");
                response = "{\"data\":{\"productVariantsBulkUpdate\":{\"productVariants\":[],\"userErrors\":[]}}}";
            } else if (query.contains("MetafieldsSet")) {
                operations.add("MetafieldsSet");
                metafieldsSetVariables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
                response = "{\"data\":{\"metafieldsSet\":{\"metafields\":[],\"userErrors\":[]}}}";
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

    @Test
    void importUpdatesMigratedProductInPlaceWithoutProductSet() {
        ShopifySyncService service = service(migratedStoreHttp());

        SyncResult result = service.importProduct("SAMPLE-001");

        assertThat(result.shopifyProductId()).isEqualTo("gid://shopify/Product/900");
        assertThat(result.handle()).isEqualTo("p-8123-sample-polo");
        assertThat(result.updated()).isTrue();
        assertThat(result.variantCount()).isEqualTo(2); // SKU-matched variants only, not PSLEGACY-XL
        assertThat(result.inventoryUpdated()).isEqualTo(2);

        // Never productSet a migrated product: declarative, it would delete the sibling ids'
        // variants and replace migrated content. Inventory + price per matched variant only.
        assertThat(operations).doesNotContain("ProductSet", "MetaobjectByHandle");
        assertThat(operations).contains("ImportedProducts", "InventorySet", "VariantsUpdate", "MetafieldsSet");

        // Only the mutable ps_last_sync_at is stamped; ps_source=migration stays untouched.
        List<?> stamped = (List<?>) metafieldsSetVariables.get("metafields");
        assertThat(stamped).extracting(m -> String.valueOf(((Map<?, ?>) m).get("key")))
                .containsExactly("ps_last_sync_at");
    }

    @Test
    void listImportedProductIdsCoversMigratedSiblingIds() {
        ShopifySyncService service = service(migratedStoreHttp());

        List<String> ids = service.listImportedProductIds();

        // Canonical + every list member, deduped case-insensitively.
        assertThat(ids).containsExactly("sample-001", "CM777");
    }

    /**
     * The catalog table asks this once per visible row. It must be answered from the cached
     * supplier-id index: paging the store per row made a 25-row page cost 25 full paginations of
     * every PromoStandards-tagged product (and the answer is a field the list already carries).
     */
    @Test
    void isImportedAnswersFromTheCachedIndexInsteadOfRepagingPerRow() {
        ShopifySyncService service = service(migratedStoreHttp());

        assertThat(service.isImported("SAMPLE-001")).isTrue();   // canonical id (stored lower-cased)
        assertThat(service.isImported("cm777")).isTrue();        // sibling id, case-insensitive
        assertThat(service.isImported("NOT-IN-STORE")).isFalse();

        assertThat(operations).containsExactly("ImportedProducts"); // one pass for all three rows
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
