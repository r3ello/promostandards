package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.discount.DiscountProperties;
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

    /** Defaults: the store index reads the ladder from the discount app's own metafield. */
    private static final DiscountProperties DISCOUNTS = new DiscountProperties(null, null, null, null);

    /** Defaults: the supplier's images replace the product's, and each variant gets its colour's. */
    private static final ImageProperties IMAGES = new ImageProperties(null, null);

    private final List<String> operations = new ArrayList<>();
    private final Map<String, Map<?, ?>> variablesByOperation = new java.util.LinkedHashMap<>();
    private Map<?, ?> productSetVariables;
    private Map<?, ?> metafieldsSetVariables;

    /** The variables the last call of one GraphQL operation was made with. */
    private Map<?, ?> varsOf(String operation) {
        return variablesByOperation.get(operation);
    }

    /** A GraphQL variable list, typed so AssertJ's extracting/containsExactly stay usable. */
    @SuppressWarnings("unchecked")
    private static List<Object> objects(Object value) {
        return (List<Object>) value;
    }

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
        // A grouped migrated product: the sibling id answers for its whole family, CM778 included.
        when(catalog.aggregate("CM777")).thenReturn(sibling());
        when(catalog.aggregate("CM778")).thenReturn(new SupplierProduct("CM778", "Sample Cap", null,
                null, null, List.of(), List.of(new Variant("CM778", "Forest", "S", "CM778-S",
                new BigDecimal("4.00"), null, 7, List.of(), null, null)), List.of(), List.of(), List.of()));

        return new ShopifySyncService(gql, catalog, mapper, policy, shopify, syncProps, new ObjectMapper(),
                CatalogTestSupport.providerOf(syncState), DISCOUNTS, IMAGES);
    }

    private static SupplierProduct sample() {
        return new SupplierProduct("SAMPLE-001", "Sample Polo", "<p>x</p>", "Trophy Apparel", "Polos",
                List.of("Polos"),
                List.of(
                        new Variant("SAMPLE-001-RED", "Red", "S", "SAMPLE-001-RED-S",
                                new BigDecimal("9.50"), new BigDecimal("12.00"), 1200, List.of(), null, null),
                        new Variant("SAMPLE-001-RED", "Red", "M", "SAMPLE-001-RED-M",
                                new BigDecimal("9.50"), new BigDecimal("12.00"), 350, List.of(), null, null)),
                List.of(), List.of(), List.of());
    }

    /** What PaceSetter answers for a sibling id: its own part plus the rest of its family. */
    private static SupplierProduct sibling() {
        return new SupplierProduct("CM777", "Sample Cap", "<p>c</p>", "Trophy Apparel", "Caps",
                List.of("Caps"),
                List.of(
                        new Variant("CM777", "Navy", "S", "CM777-S",
                                new BigDecimal("4.00"), null, 5, List.of(), null, null),
                        new Variant("CM778", "Forest", "S", "CM778-S",
                                new BigDecimal("4.00"), null, 0, List.of(), null, null)),
                List.of(), List.of(), List.of());
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
     * Store state for the migrated-product tests: nothing under the app handle, and one
     * migration-created product ({@code p-8123-sample-polo}) exactly as Matrixify left it — the
     * default {@code Title / Default Title} option, one legacy variant with a {@code PS} SKU and
     * untracked inventory, no variant identity metafield. Its {@code ps_product_ids} covers
     * SAMPLE-001 (deliberately lower-cased, to prove case-insensitive resolution) plus a sibling
     * CM777.
     */
    private ShopifyHttp migratedStoreHttp() {
        return (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            Map<?, ?> variables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
            String response;
            String operation;
            if (query.contains("ProductByHandle")) {
                operation = "ProductByHandle";
                response = String.valueOf(variables.get("query")).contains("p-8123-sample-polo") ? """
                        {"data":{"products":{"nodes":[{
                          "id":"gid://shopify/Product/900",
                          "handle":"p-8123-sample-polo",
                          "legacySku":{"value":"PS11592"},
                          "options":[{"id":"gid://shopify/ProductOption/1","name":"Title","position":1,
                            "optionValues":[{"id":"gid://shopify/ProductOptionValue/1","name":"Default Title"}]}],
                          "variants":{"nodes":[
                            {"id":"gid://shopify/ProductVariant/93","sku":"PSLEGACY-XL",
                             "selectedOptions":[{"name":"Title","value":"Default Title"}],
                             "inventoryItem":{"id":"gid://shopify/InventoryItem/193","tracked":false}}
                          ]}
                        }]}}}"""
                        : "{\"data\":{\"products\":{\"nodes\":[]}}}";
            } else if (query.contains("ImportedProducts")) {
                operation = "ImportedProducts";
                response = """
                        {"data":{"products":{"pageInfo":{"hasNextPage":false},"nodes":[{
                          "id":"gid://shopify/Product/900",
                          "handle":"p-8123-sample-polo",
                          "psId":{"value":"sample-001"},
                          "psIds":{"value":"[\\"sample-001\\",\\"CM777\\"]"},
                          "psSource":{"value":"migration"},
                          "discounts":{"value":"{\\"currencyCode\\":\\"USD\\",\\"tiers\\":[]}"}
                        }]}}}""";
            } else if (query.contains("ProductOptionUpdate")) {
                operation = "ProductOptionUpdate";
                response = "{\"data\":{\"productOptionUpdate\":{\"product\":{\"id\":\"gid://shopify/Product/900\"},\"userErrors\":[]}}}";
            } else if (query.contains("ProductOptionsCreate")) {
                operation = "ProductOptionsCreate";
                response = "{\"data\":{\"productOptionsCreate\":{\"product\":{\"id\":\"gid://shopify/Product/900\"},\"userErrors\":[]}}}";
            } else if (query.contains("VariantsCreate")) {
                operation = "VariantsCreate";
                response = "{\"data\":{\"productVariantsBulkCreate\":{\"productVariants\":[],\"userErrors\":[]}}}";
            } else if (query.contains("VariantsUpdate")) {
                operation = "VariantsUpdate";
                response = "{\"data\":{\"productVariantsBulkUpdate\":{\"productVariants\":[],\"userErrors\":[]}}}";
            } else if (query.contains("InventoryActivate")) {
                operation = "InventoryActivate";
                response = "{\"data\":{\"inventoryBulkToggleActivation\":{\"inventoryItem\":{\"id\":\"gid://shopify/InventoryItem/193\"},\"userErrors\":[]}}}";
            } else if (query.contains("InventorySet")) {
                operation = "InventorySet";
                response = "{\"data\":{\"inventorySetQuantities\":{\"inventoryAdjustmentGroup\":{\"createdAt\":\"now\"},\"userErrors\":[]}}}";
            } else if (query.contains("MetafieldsSet")) {
                operation = "MetafieldsSet";
                metafieldsSetVariables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
                response = "{\"data\":{\"metafieldsSet\":{\"metafields\":[],\"userErrors\":[]}}}";
            } else {
                throw new IllegalStateException("unexpected query: " + query);
            }
            operations.add(operation);
            variablesByOperation.put(operation, (Map<?, ?>) ((Map<?, ?>) body).get("variables"));
            try {
                return MAPPER.readTree(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * A migrated product carries none of the supplier's variants, so syncing one used to match
     * nothing and do nothing. It now adopts the legacy variant and creates the rest — never with
     * {@code productSet}, which is declarative and would delete the sibling ids' variants along with
     * the migrated title, body and images.
     */
    @Test
    void importAdoptsTheMigratedVariantAndCreatesTheMissingOnes() {
        ShopifySyncService service = service(migratedStoreHttp());

        SyncResult result = service.importProduct("SAMPLE-001");

        assertThat(result.shopifyProductId()).isEqualTo("gid://shopify/Product/900");
        assertThat(result.handle()).isEqualTo("p-8123-sample-polo");
        assertThat(result.updated()).isTrue();
        // SAMPLE-001 {Red S, Red M} + the sibling family {CM777 Navy S, CM778 Forest S}
        assertThat(result.variantCount()).isEqualTo(4);
        assertThat(operations).doesNotContain("ProductSet", "MetaobjectByHandle");
        // The supplier sent no images for this product, so its own are left exactly as they are.
        // Deleting first and finding nothing to publish would strip a product over a supplier fault
        // — PaceSetter's Media service faults outright on whole families.
        assertThat(operations).doesNotContain("FileDelete", "ProductAddMedia", "VariantAppendMedia");

        // The default Title option becomes Color, and Size is added, before any variant is written.
        assertThat(operations).containsSubsequence("ProductOptionUpdate", "ProductOptionsCreate",
                "VariantsUpdate", "VariantsCreate");
        Map<?, ?> renamed = (Map<?, ?>) varsOf("ProductOptionUpdate").get("option");
        assertThat(renamed.get("name")).isEqualTo("Color");
        assertThat((List<?>) varsOf("ProductOptionUpdate").get("optionValuesToUpdate")).singleElement()
                .satisfies(v -> assertThat(((Map<?, ?>) v).get("name")).isEqualTo("Red"));

        // The legacy variant is kept (same id) and rewritten as the supplier's first variant.
        List<?> updated = (List<?>) varsOf("VariantsUpdate").get("variants");
        assertThat(updated).singleElement().satisfies(v -> {
            Map<?, ?> variant = (Map<?, ?>) v;
            assertThat(variant.get("id")).isEqualTo("gid://shopify/ProductVariant/93");
            Map<?, ?> inventoryItem = (Map<?, ?>) variant.get("inventoryItem");
            // The store's own numbering: migration.legacy_sku + what tells the parts apart. These
            // fixture ids share no prefix, so each keeps its whole part id (and its size, because
            // SAMPLE-001-RED is sold in two).
            assertThat(inventoryItem.get("sku")).isEqualTo("PS11592-SAMPLE-001-RED-S");
            assertThat(inventoryItem.get("tracked")).isEqualTo(true);
            // The join back to the supplier moved to a metafield, since the SKU no longer carries it.
            assertThat(objects(variant.get("metafields"))).anySatisfy(m -> {
                Map<?, ?> field = (Map<?, ?>) m;
                assertThat(field.get("namespace")).isEqualTo("trophy_sync");
                assertThat(field.get("key")).isEqualTo("vendor_sku");
                assertThat(field.get("value")).isEqualTo("SAMPLE-001-RED");
            });
            List<Object> optionValues = objects(variant.get("optionValues"));
            assertThat(optionValues).extracting(o -> String.valueOf(((Map<?, ?>) o).get("name")))
                    .containsExactly("Red", "S");
        });

        // The other three are created, each carrying its own supplier id as the variant metafield.
        assertThat(varsOf("VariantsCreate").get("strategy")).isEqualTo("PRESERVE_STANDALONE_VARIANT");
        List<Object> created = objects(varsOf("VariantsCreate").get("variants"));
        assertThat(created).hasSize(3);
        assertThat(created).extracting(v -> {
            List<?> metafields = (List<?>) ((Map<?, ?>) v).get("metafields");
            return String.valueOf(((Map<?, ?>) metafields.get(0)).get("value"));
        }).containsExactly("SAMPLE-001-RED", "CM777", "CM778");

        // The adopted variant is untracked and stocked nowhere, so it is activated before its
        // quantity is set; the created ones get theirs inside productVariantsBulkCreate.
        assertThat(operations).containsSubsequence("InventoryActivate", "InventorySet");
        assertThat(result.inventoryUpdated()).isEqualTo(1);

        // CM778 is a product of its own that ps_product_ids never listed: the list grows to cover it
        // (one MetafieldsSet), on top of the ps_last_sync_at stamp (another).
        assertThat(operations.stream().filter("MetafieldsSet"::equals).toList()).hasSize(2);
    }

    /** A store product built on options this app does not model keeps its variants untouched. */
    @Test
    void importLeavesUnmodelledOptionsAlone() {
        ShopifyHttp http = (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            Map<?, ?> variables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
            String response;
            String operation;
            if (query.contains("ProductByHandle")) {
                operation = "ProductByHandle";
                response = String.valueOf(variables.get("query")).contains("p-8123-sample-polo") ? """
                        {"data":{"products":{"nodes":[{
                          "id":"gid://shopify/Product/900",
                          "handle":"p-8123-sample-polo",
                          "options":[{"id":"gid://shopify/ProductOption/1","name":"Material","position":1,
                            "optionValues":[{"id":"gid://shopify/ProductOptionValue/1","name":"Oak"}]}],
                          "variants":{"nodes":[
                            {"id":"gid://shopify/ProductVariant/93","sku":"SAMPLE-001-RED-S",
                             "selectedOptions":[{"name":"Material","value":"Oak"}],
                             "inventoryItem":{"id":"gid://shopify/InventoryItem/193","tracked":true}}
                          ]}
                        }]}}}"""
                        : "{\"data\":{\"products\":{\"nodes\":[]}}}";
            } else if (query.contains("ImportedProducts")) {
                operation = "ImportedProducts";
                response = """
                        {"data":{"products":{"pageInfo":{"hasNextPage":false},"nodes":[{
                          "id":"gid://shopify/Product/900",
                          "handle":"p-8123-sample-polo",
                          "psId":{"value":"SAMPLE-001"},
                          "psIds":{"value":"[\\"SAMPLE-001\\"]"},
                          "psSource":{"value":"migration"}
                        }]}}}""";
            } else if (query.contains("VariantsUpdate")) {
                operation = "VariantsUpdate";
                response = "{\"data\":{\"productVariantsBulkUpdate\":{\"productVariants\":[],\"userErrors\":[]}}}";
            } else if (query.contains("InventorySet")) {
                operation = "InventorySet";
                response = "{\"data\":{\"inventorySetQuantities\":{\"inventoryAdjustmentGroup\":{\"createdAt\":\"now\"},\"userErrors\":[]}}}";
            } else if (query.contains("MetafieldsSet")) {
                operation = "MetafieldsSet";
                metafieldsSetVariables = (Map<?, ?>) ((Map<?, ?>) body).get("variables");
                response = "{\"data\":{\"metafieldsSet\":{\"metafields\":[],\"userErrors\":[]}}}";
            } else {
                throw new IllegalStateException("unexpected query: " + query);
            }
            operations.add(operation);
            variablesByOperation.put(operation, (Map<?, ?>) ((Map<?, ?>) body).get("variables"));
            try {
                return MAPPER.readTree(response);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifySyncService service = service(http);

        SyncResult result = service.importProduct("SAMPLE-001");

        // No option is rewritten and no variant created; the SKU-matched one is still refreshed.
        assertThat(operations).doesNotContain("ProductOptionUpdate", "ProductOptionsCreate",
                "VariantsCreate", "ProductSet");
        assertThat(operations).contains("VariantsUpdate", "InventorySet");
        assertThat(result.inventoryUpdated()).isEqualTo(1);
        // The sync's own bookkeeping is stamped in its namespace — including source=migration, which
        // is what this product is; the migration's custom.ps_* keys are never touched.
        List<?> stamped = (List<?>) metafieldsSetVariables.get("metafields");
        assertThat(stamped).allSatisfy(m ->
                assertThat(((Map<?, ?>) m).get("namespace")).isEqualTo("trophy_sync"));
        assertThat(stamped).extracting(m -> String.valueOf(((Map<?, ?>) m).get("key")))
                .containsExactlyInAnyOrder("last_sync_at", "vendor", "source");
        assertThat(stamped).anySatisfy(m -> {
            Map<?, ?> field = (Map<?, ?>) m;
            if ("source".equals(field.get("key"))) {
                assertThat(field.get("value")).isEqualTo("migration");
            }
        });
    }

    @Test
    void listImportedProductIdsCoversMigratedSiblingIds() {
        ShopifySyncService service = service(migratedStoreHttp());

        List<String> ids = service.listImportedProductIds();

        // Canonical + every list member, deduped case-insensitively.
        assertThat(ids).containsExactly("sample-001", "CM777");
    }

    /**
     * The way this store is actually run: products are migrated in with Matrixify and imported from
     * the console minutes later. If the create path trusted a five-minute-old index, it would decide
     * the product is not in the store and create a duplicate under the app's own handle — so on a
     * miss it repages before creating, and finds it.
     */
    @Test
    void repagesTheStoreBeforeCreatingRatherThanTrustingAStaleIndex() {
        // The store gains the migrated product between the two listings: the first answers as it did
        // before the migration ran, the second as it is now.
        ShopifyHttp migratedAfterFirstListing = new ShopifyHttp() {
            private final ShopifyHttp real = migratedStoreHttp();
            private boolean listed;

            @Override
            public JsonNode postJson(String path, Object body, Map<String, String> headers) {
                String query = String.valueOf(((Map<?, ?>) body).get("query"));
                if (query.contains("ImportedProducts") && !listed) {
                    listed = true;
                    operations.add("ImportedProducts");
                    try {
                        return MAPPER.readTree(
                                "{\"data\":{\"products\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[]}}}");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                return real.postJson(path, body, headers);
            }
        };
        ShopifySyncService service = service(migratedAfterFirstListing);

        // The catalog warms the index while the product is not in the store yet.
        assertThat(service.isImported("SAMPLE-001")).isFalse();
        operations.clear();

        SyncResult result = service.importProduct("SAMPLE-001");

        assertThat(operations).contains("ImportedProducts");   // it looked again before creating
        assertThat(operations).doesNotContain("ProductSet");   // so it adopted instead of duplicating
        assertThat(result.shopifyProductId()).isEqualTo("gid://shopify/Product/900");
    }

    /**
     * The discounts badge, like the imported one, is answered from the cached index — the published
     * ladder rides along on the same listing rather than costing a lookup per row.
     */
    @Test
    void readsThePublishedDiscountsFromTheSameIndex() {
        ShopifySyncService service = service(migratedStoreHttp());

        assertThat(service.hasDiscounts("SAMPLE-001")).isTrue();
        assertThat(service.hasDiscounts("cm777")).isTrue();   // sibling id of the same product
        assertThat(service.hasDiscounts("NOT-IN-STORE")).isFalse();

        // The namespace and key it asked Shopify for are the configured ones, not hard-coded.
        assertThat(varsOf("ImportedProducts").get("discountNamespace")).isEqualTo("trophy_discount");
        assertThat(varsOf("ImportedProducts").get("discountKey")).isEqualTo("discount_tiers");

        assertThat(operations).containsExactly("ImportedProducts");
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
