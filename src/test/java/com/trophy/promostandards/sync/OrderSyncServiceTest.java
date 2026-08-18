package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.db.OrderStore;
import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.model.OrderShipment.ShipmentItem;
import com.trophy.promostandards.ordershipment.model.OrderShipment.ShipmentPackage;
import com.trophy.promostandards.ordershipment.service.OrderShipmentService;
import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.model.OrderStatus.OrderStatusDetail;
import com.trophy.promostandards.orderstatus.service.OrderStatusService;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyHttp;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.shopify.ShopifyTokenService;
import com.trophy.promostandards.sync.OrderSyncService.OrderSyncResult;
import com.trophy.promostandards.shopify.ShopifyRetryProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderSyncServiceTest {

    /** Retry fast in tests: the throttling backoff is behaviour, not something to wait out. */
    private static final ShopifyRetryProperties TEST_RETRY =
            new ShopifyRetryProperties(3, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> operations = new ArrayList<>();

    private ShopifyHttp routingHttp() {
        return (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            String response;
            if (query.contains("OrderById")) {
                operations.add("OrderById");
                // The "deleted" id models an order that no longer exists: Shopify returns data with
                // a null order rather than an error.
                String id = String.valueOf(((Map<?, ?>) ((Map<?, ?>) body).get("variables")).get("id"));
                response = id.endsWith("deleted") ? "{\"data\":{\"order\":null}}" : """
                        {"data":{"order":{
                          "id":"gid://shopify/Order/500","name":"PO-1001",
                          "fulfillmentOrders":{"nodes":[{"id":"gid://shopify/FulfillmentOrder/9","status":"OPEN"}]}
                        }}}""";
            } else if (query.contains("OrderByPo")) {
                operations.add("OrderByPo");
                response = """
                        {"data":{"orders":{"nodes":[{
                          "id":"gid://shopify/Order/500","name":"PO-1001",
                          "fulfillmentOrders":{"nodes":[{"id":"gid://shopify/FulfillmentOrder/9","status":"OPEN"}]}
                        }]}}}""";
            } else if (query.contains("MetafieldsSet")) {
                operations.add("MetafieldsSet");
                response = "{\"data\":{\"metafieldsSet\":{\"metafields\":[{\"id\":\"gid://m/1\",\"key\":\"supplier_status\"}],\"userErrors\":[]}}}";
            } else if (query.contains("FulfillmentCreate")) {
                operations.add("FulfillmentCreate");
                response = "{\"data\":{\"fulfillmentCreate\":{\"fulfillment\":{\"id\":\"gid://f/1\",\"status\":\"SUCCESS\",\"trackingInfo\":[{\"number\":\"1Z999\"}]},\"userErrors\":[]}}}";
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

    /** In-memory order bookkeeping; null models an install running without a database. */
    private OrderStore orderStore;

    private OrderSyncService service(ShopifyHttp http) {
        ShopifyProperties shopify = new ShopifyProperties("shop.myshopify.com", "id", "secret",
                "whsec", "2026-04", "gid://shopify/Location/1");
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        ShopifyGraphQLClient gql = new ShopifyGraphQLClient(http, tokens, shopify, TEST_RETRY);

        OrderShipmentService shipments = mock(OrderShipmentService.class);
        when(shipments.getShippedSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OrderShipment("PO-1001", true, List.of(
                        new ShipmentPackage("SO-1", "1Z999", "UPS", "Ground", Instant.now(),
                                "Newark", "NJ", "07097", "US",
                                List.of(new ShipmentItem("SAMPLE-001", "SAMPLE-001-RED", new BigDecimal("100"))))))));

        OrderStatusService statuses = mock(OrderStatusService.class);
        when(statuses.getUpdatedSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(
                new OrderStatus("PO-1001", List.of(
                        new OrderStatusDetail("FO-1", 60, "Shipped", null, null, null, false, Instant.now())))));

        return new OrderSyncService(shipments, statuses, gql, CatalogTestSupport.providerOf(orderStore));
    }

    @Test
    void matchesOrderStampsStatusAndCreatesFulfillment() {
        OrderSyncService service = service(routingHttp());

        OrderSyncResult result = service.syncOrders(Instant.now().minusSeconds(3600));

        assertThat(result.posProcessed()).isEqualTo(1);
        assertThat(result.ordersMatched()).isEqualTo(1);
        assertThat(result.fulfillmentsCreated()).isEqualTo(1);
        assertThat(operations).containsExactly("OrderByPo", "MetafieldsSet", "FulfillmentCreate");
    }

    /**
     * The bug this closes: the order cursor rewinds a full day whenever the app restarts, so the same
     * shipment is processed again — and each pass used to create another fulfillment, sending the
     * customer another shipping notification for a parcel that had already shipped.
     */
    @Test
    void doesNotFulfillTheSameShipmentTwice() {
        orderStore = new InMemoryOrderStore();
        OrderSyncService service = service(routingHttp());

        OrderSyncResult first = service.syncOrders(Instant.now().minusSeconds(3600));
        operations.clear();
        OrderSyncResult second = service.syncOrders(Instant.now().minusSeconds(3600));

        assertThat(first.fulfillmentsCreated()).isEqualTo(1);
        assertThat(second.fulfillmentsCreated()).isZero();
        assertThat(second.shipmentsSkipped()).isEqualTo(1);
        assertThat(operations).doesNotContain("FulfillmentCreate");
    }

    /** Without a database there is nothing to dedupe against — which is exactly the old behaviour. */
    @Test
    void stillDuplicatesWithoutPersistence() {
        orderStore = null;
        OrderSyncService service = service(routingHttp());

        service.syncOrders(Instant.now().minusSeconds(3600));
        OrderSyncResult second = service.syncOrders(Instant.now().minusSeconds(3600));

        assertThat(second.fulfillmentsCreated()).isEqualTo(1);
    }

    /** Once a PO is matched, later runs resolve it by id instead of re-running the name-search guess. */
    @Test
    void remembersTheMatchedOrderAndResolvesItByIdAfterwards() {
        orderStore = new InMemoryOrderStore();
        OrderSyncService service = service(routingHttp());

        service.syncOrders(Instant.now().minusSeconds(3600));
        assertThat(orderStore.findOrder("PO-1001").orElseThrow().shopifyOrderGid())
                .isEqualTo("gid://shopify/Order/500");

        operations.clear();
        service.syncOrders(Instant.now().minusSeconds(3600));

        assertThat(operations).contains("OrderById").doesNotContain("OrderByPo");
    }

    /** A stored id that no longer resolves (order deleted) must fall back to searching, not give up. */
    @Test
    void fallsBackToSearchingWhenTheStoredOrderIsGone() {
        InMemoryOrderStore store = new InMemoryOrderStore();
        store.saveOrder("PO-1001", "gid://shopify/Order/deleted", "PO-1001");
        orderStore = store;
        OrderSyncService service = service(routingHttp());

        OrderSyncResult result = service.syncOrders(Instant.now().minusSeconds(3600));

        assertThat(result.ordersMatched()).isEqualTo(1);
        assertThat(operations).contains("OrderById", "OrderByPo");
        assertThat(store.findOrder("PO-1001").orElseThrow().shopifyOrderGid())
                .isEqualTo("gid://shopify/Order/500");   // re-matched and corrected
    }

    /** In-memory {@link OrderStore}; "gid://shopify/Order/deleted" models an order that is gone. */
    private static final class InMemoryOrderStore implements OrderStore {
        private final Map<String, OrderLink> orders = new java.util.HashMap<>();
        private final java.util.Set<String> pushed = new java.util.HashSet<>();
        private final Map<String, Instant> watermarks = new java.util.HashMap<>();

        @Override
        public java.util.Optional<OrderLink> findOrder(String poNumber) {
            return java.util.Optional.ofNullable(orders.get(poNumber));
        }

        @Override
        public void saveOrder(String poNumber, String gid, String name) {
            orders.put(poNumber, new OrderLink(poNumber, gid, name));
        }

        @Override
        public boolean isShipmentPushed(String poNumber, String shipmentKey) {
            return pushed.contains(poNumber + "|" + shipmentKey);
        }

        @Override
        public void recordShipmentPushed(String poNumber, String shipmentKey, String fulfillmentGid) {
            pushed.add(poNumber + "|" + shipmentKey);
        }

        @Override
        public java.util.Optional<Instant> watermark(String job) {
            return java.util.Optional.ofNullable(watermarks.get(job));
        }

        @Override
        public void saveWatermark(String job, Instant cursorAt) {
            watermarks.put(job, cursorAt);
        }
    }
}
