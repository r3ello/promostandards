package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.db.SyncStateStore;
import com.trophy.promostandards.db.SyncStateStore.Kind;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyHttp;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.shopify.ShopifyRetryProperties;
import com.trophy.promostandards.shopify.ShopifyTokenService;
import com.trophy.promostandards.sync.ShopifySyncService.Outcome;
import com.trophy.promostandards.sync.ShopifySyncService.RefreshResult;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.trophy.promostandards.sync.CatalogTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The incremental refresh: what makes scheduled syncing affordable. Every scheduled pass used to
 * push all ~943 imported products regardless of whether anything had changed; these tests pin the
 * behaviour that replaced it, including the failure modes that would either re-push everything or
 * quietly stop pushing at all.
 */
class IncrementalSyncTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> operations = new ArrayList<>();
    private RecordingSyncStateStore state;
    private boolean shopifyFails;

    @BeforeEach
    void setUp() {
        state = new RecordingSyncStateStore();
        operations.clear();
        shopifyFails = false;
    }

    /** In-memory sync state; {@code failing} models the database being unreachable. */
    private static final class RecordingSyncStateStore implements SyncStateStore {
        private final Map<String, State> states = new HashMap<>();
        private final List<String> runs = new ArrayList<>();
        boolean failing;

        @Override
        public Optional<State> find(String productId, Kind kind) {
            if (failing) {
                throw new IllegalStateException("connection refused");
            }
            return Optional.ofNullable(states.get(productId + "|" + kind));
        }

        @Override
        public void recordSuccess(String productId, Kind kind, String payloadHash) {
            states.put(productId + "|" + kind, new State(payloadHash, 0, null));
        }

        @Override
        public void recordFailure(String productId, Kind kind, String error) {
            State previous = states.get(productId + "|" + kind);
            int failures = previous == null ? 1 : previous.consecutiveFailures() + 1;
            states.put(productId + "|" + kind, new State(previous == null ? null : previous.payloadHash(),
                    failures, Instant.now().plus(Duration.ofMinutes(5))));
        }

        @Override
        public void recordRun(String job, Instant startedAt, int processed, int pushed, int failed,
                              int skipped) {
            runs.add(job + ":" + processed + "/" + pushed + "/" + failed + "/" + skipped);
        }

        void backOffUntil(String productId, Kind kind, Instant until) {
            states.put(productId + "|" + kind, new State("stale-hash", 3, until));
        }
    }

    private ShopifySyncService service(SupplierProduct product) {
        ShopifyHttp http = (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            String response;
            if (query.contains("ProductByHandle")) {
                operations.add("ProductByHandle");
                response = """
                        {"data":{"products":{"nodes":[{
                          "id":"gid://shopify/Product/900","handle":"ps-pacesetter-sample-001",
                          "variants":{"nodes":[{"id":"gid://shopify/ProductVariant/91","sku":"SAMPLE-001-RED-S",
                            "inventoryItem":{"id":"gid://shopify/InventoryItem/191"}}]}
                        }]}}}""";
            } else if (query.contains("InventorySet")) {
                operations.add("InventorySet");
                if (shopifyFails) {
                    response = "{\"data\":{\"inventorySetQuantities\":{\"userErrors\":"
                            + "[{\"field\":\"quantities\",\"message\":\"location not found\"}]}}}";
                } else {
                    response = "{\"data\":{\"inventorySetQuantities\":{\"inventoryAdjustmentGroup\":"
                            + "{\"createdAt\":\"now\"},\"userErrors\":[]}}}";
                }
            } else if (query.contains("VariantsUpdate")) {
                operations.add("VariantsUpdate");
                response = "{\"data\":{\"productVariantsBulkUpdate\":{\"productVariants\":[],\"userErrors\":[]}}}";
            } else if (query.contains("MetafieldsSet")) {
                operations.add("MetafieldsSet");
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

        ShopifyProperties shopify = new ShopifyProperties("shop.myshopify.com", "id", "secret",
                "whsec", "2026-04", "gid://shopify/Location/1");
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        ShopifyGraphQLClient gql = new ShopifyGraphQLClient(http, tokens, shopify,
                new ShopifyRetryProperties(1, Duration.ofMillis(1), Duration.ofMillis(1)));

        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        PricingPolicy policy = new PricingPolicy(props);
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.aggregate("SAMPLE-001")).thenReturn(product);

        return new ShopifySyncService(gql, catalog,
                new ShopifyProductMapper(props, policy, new ObjectMapper(), shopify),
                policy, shopify, props, new ObjectMapper(), providerOf(state));
    }

    private static SupplierProduct product(int onHand) {
        return new SupplierProduct("SAMPLE-001", "Sample Polo", null, null, null, List.of(),
                List.of(new Variant("SAMPLE-001-RED", "Red", "S", "SAMPLE-001-RED-S",
                        new BigDecimal("9.50"), new BigDecimal("12.00"), onHand, List.of())),
                List.of(), List.of());
    }

    /** The whole point: the second run of unchanged data costs nothing at Shopify. */
    @Test
    void skipsAProductWhoseValuesHaveNotChangedSinceTheLastPush() {
        ShopifySyncService service = service(product(10));

        assertThat(service.refresh("SAMPLE-001", Kind.INVENTORY, false, false).outcome())
                .isEqualTo(Outcome.PUSHED);
        operations.clear();

        RefreshResult second = service.refresh("SAMPLE-001", Kind.INVENTORY, false, false);

        assertThat(second.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(operations).isEmpty();   // not one Shopify call
    }

    @Test
    void pushesAgainOnceTheQuantityChanges() {
        service(product(10)).refresh("SAMPLE-001", Kind.INVENTORY, false, false);
        operations.clear();

        RefreshResult result = service(product(25)).refresh("SAMPLE-001", Kind.INVENTORY, false, false);

        assertThat(result.outcome()).isEqualTo(Outcome.PUSHED);
        assertThat(operations).contains("InventorySet");
    }

    /** A dry run must evaluate everything and write nothing — including the digest. */
    @Test
    void dryRunReportsWhatItWouldPushWithoutTouchingShopify() {
        ShopifySyncService service = service(product(10));

        assertThat(service.refresh("SAMPLE-001", Kind.INVENTORY, true, false).outcome())
                .isEqualTo(Outcome.WOULD_PUSH);
        assertThat(operations).isEmpty();
        // Nothing was recorded, so a real run afterwards still considers the product due.
        assertThat(service.refresh("SAMPLE-001", Kind.INVENTORY, false, false).outcome())
                .isEqualTo(Outcome.PUSHED);
    }

    /**
     * Writing the digest before Shopify accepted the mutation would mark a failed push as done and
     * the product would never retry.
     */
    @Test
    void aFailedPushLeavesTheProductDueAndBacksOff() {
        ShopifySyncService service = service(product(10));
        shopifyFails = true;

        RefreshResult failure = service.refresh("SAMPLE-001", Kind.INVENTORY, false, false);
        assertThat(failure.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(state.find("SAMPLE-001", Kind.INVENTORY).orElseThrow().payloadHash()).isNull();

        // Still inside the backoff window, so the next pass leaves it alone rather than retrying it
        // at the front of every run.
        assertThat(service.refresh("SAMPLE-001", Kind.INVENTORY, false, false).outcome())
                .isEqualTo(Outcome.BACKING_OFF);
    }

    /** A person clicking "Sync" means now: neither the digest nor a backoff window should stop it. */
    @Test
    void aManualSyncIgnoresTheDigestAndTheBackoff() {
        ShopifySyncService service = service(product(10));
        service.refresh("SAMPLE-001", Kind.INVENTORY, false, false);
        state.backOffUntil("SAMPLE-001", Kind.INVENTORY, Instant.now().plus(Duration.ofHours(1)));
        operations.clear();

        assertThat(service.syncInventory("SAMPLE-001")).isEqualTo(1);
        assertThat(operations).contains("InventorySet");
    }

    /** And a manual sync surfaces its failure to the caller instead of swallowing it into a result. */
    @Test
    void aManualSyncSurfacesPushFailures() {
        ShopifySyncService service = service(product(10));
        shopifyFails = true;

        assertThatThrownBy(() -> service.syncInventory("SAMPLE-001"))
                .isInstanceOf(ShopifySyncException.class);
    }

    /**
     * The dangerous failure mode: if unreadable state were treated as "never pushed", a database
     * blip would make the next run push the entire catalog.
     */
    @Test
    void skipsRatherThanPushingBlindWhenSyncStateIsUnreachable() {
        ShopifySyncService service = service(product(10));
        state.failing = true;

        RefreshResult result = service.refresh("SAMPLE-001", Kind.INVENTORY, false, false);

        assertThat(result.outcome()).isEqualTo(Outcome.STATE_UNAVAILABLE);
        assertThat(operations).isEmpty();
    }

    /** Without persistence configured the service behaves exactly as it did before: always push. */
    @Test
    void pushesEveryTimeWhenThereIsNoPersistence() {
        ShopifyProperties shopify = new ShopifyProperties("shop.myshopify.com", "id", "secret",
                "whsec", "2026-04", "gid://shopify/Location/1");
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        PricingPolicy policy = new PricingPolicy(props);
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.aggregate("SAMPLE-001")).thenReturn(product(10));
        ShopifyHttp http = (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            operations.add(query.contains("ProductByHandle") ? "ProductByHandle"
                    : query.contains("InventorySet") ? "InventorySet" : "other");
            try {
                return MAPPER.readTree(query.contains("ProductByHandle") ? """
                        {"data":{"products":{"nodes":[{"id":"gid://shopify/Product/900",
                          "handle":"ps-pacesetter-sample-001","variants":{"nodes":[]}}]}}}"""
                        : "{\"data\":{\"inventorySetQuantities\":{\"userErrors\":[]},"
                                + "\"metafieldsSet\":{\"metafields\":[],\"userErrors\":[]}}}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifySyncService service = new ShopifySyncService(
                new ShopifyGraphQLClient(http, tokens, shopify,
                        new ShopifyRetryProperties(1, Duration.ofMillis(1), Duration.ofMillis(1))),
                catalog, new ShopifyProductMapper(props, policy, new ObjectMapper(), shopify),
                policy, shopify, props, new ObjectMapper(), providerOf(null));

        assertThat(service.refresh("SAMPLE-001", Kind.INVENTORY, false, false).outcome())
                .isEqualTo(Outcome.PUSHED);
        operations.clear();
        assertThat(service.refresh("SAMPLE-001", Kind.INVENTORY, false, false).outcome())
                .isEqualTo(Outcome.PUSHED);
        assertThat(operations).isNotEmpty();
    }
}
