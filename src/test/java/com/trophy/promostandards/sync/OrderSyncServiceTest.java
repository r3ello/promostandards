package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<String> operations = new ArrayList<>();

    private ShopifyHttp routingHttp() {
        return (path, body, headers) -> {
            String query = String.valueOf(((Map<?, ?>) body).get("query"));
            String response;
            if (query.contains("OrderByPo")) {
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

    private OrderSyncService service(ShopifyHttp http) {
        ShopifyProperties shopify = new ShopifyProperties("shop.myshopify.com", "id", "secret",
                "whsec", "2026-04", "gid://shopify/Location/1");
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        ShopifyGraphQLClient gql = new ShopifyGraphQLClient(http, tokens, shopify);

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

        return new OrderSyncService(shipments, statuses, gql);
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
}
