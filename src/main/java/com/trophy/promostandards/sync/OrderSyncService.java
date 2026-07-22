package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.service.OrderShipmentService;
import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.service.OrderStatusService;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes supplier order progress back onto Shopify orders: the latest order status is stamped as an
 * order metafield, and each shipped package becomes a Shopify fulfillment carrying carrier tracking.
 *
 * <p><b>PO matching (open integration point):</b> a supplier purchase-order number is matched to a
 * Shopify order via the search query {@code name:<po>}. This assumes the supplier PO equals the
 * Shopify order name; adjust {@link #orderQuery} to match how POs are actually recorded on orders
 * (e.g. a {@code custom.supplier_po} order metafield set when the PO is placed).
 */
@Service
public class OrderSyncService {

    private static final Logger log = LoggerFactory.getLogger(OrderSyncService.class);

    private final OrderShipmentService shipments;
    private final OrderStatusService statuses;
    private final ShopifyGraphQLClient gql;

    public OrderSyncService(OrderShipmentService shipments, OrderStatusService statuses,
                            ShopifyGraphQLClient gql) {
        this.shipments = shipments;
        this.statuses = statuses;
        this.gql = gql;
    }

    /** Result of an order sync run. */
    public record OrderSyncResult(int posProcessed, int ordersMatched, int fulfillmentsCreated) {
    }

    /** Sync every supplier order updated/shipped since {@code since} onto its Shopify order. */
    public OrderSyncResult syncOrders(Instant since) {
        Map<String, String> statusByPo = latestStatusByPo(since);
        List<OrderShipment> shipped = shipments.getShippedSince(since);

        int matched = 0;
        int fulfillments = 0;
        for (OrderShipment shipment : shipped) {
            JsonNode order = resolveOrder(shipment.purchaseOrderNumber());
            if (order == null) {
                log.info("No Shopify order matched supplier PO {}", shipment.purchaseOrderNumber());
                continue;
            }
            matched++;
            String orderGid = order.path("id").asText();
            stampStatus(orderGid, statusByPo.get(shipment.purchaseOrderNumber()));
            fulfillments += createFulfillments(order, shipment);
        }
        OrderSyncResult result = new OrderSyncResult(shipped.size(), matched, fulfillments);
        log.info("Order sync since {}: {}", since, result);
        return result;
    }

    private Map<String, String> latestStatusByPo(Instant since) {
        Map<String, String> byPo = new LinkedHashMap<>();
        for (OrderStatus status : statuses.getUpdatedSince(since)) {
            status.details().stream()
                    .max(java.util.Comparator.comparing(d -> d.validTimestamp() == null ? Instant.MIN : d.validTimestamp()))
                    .ifPresent(d -> byPo.put(status.purchaseOrderNumber(), d.statusName()));
        }
        return byPo;
    }

    private JsonNode resolveOrder(String poNumber) {
        JsonNode data = gql.execute(ShopifyGraphQL.ORDER_BY_PO, Map.of("query", orderQuery(poNumber)));
        if (data == null) {
            return null;
        }
        JsonNode nodes = data.path("orders").path("nodes");
        return nodes.isArray() && !nodes.isEmpty() ? nodes.get(0) : null;
    }

    /** Search query used to match a supplier PO to a Shopify order. See class javadoc. */
    private String orderQuery(String poNumber) {
        return "name:" + poNumber;
    }

    private void stampStatus(String orderGid, String statusName) {
        if (statusName == null || statusName.isBlank()) {
            return;
        }
        Map<String, Object> metafield = Map.of(
                "ownerId", orderGid,
                "namespace", ShopifyProductMapper.METAFIELD_NAMESPACE,
                "key", "supplier_status",
                "type", "single_line_text_field",
                "value", statusName);
        JsonNode data = gql.execute(ShopifyGraphQL.METAFIELDS_SET, Map.of("metafields", List.of(metafield)));
        checkUserErrors(data, "metafieldsSet");
    }

    private int createFulfillments(JsonNode order, OrderShipment shipment) {
        List<Map<String, Object>> lineItemsByFulfillmentOrder = new ArrayList<>();
        for (JsonNode fo : order.path("fulfillmentOrders").path("nodes")) {
            if ("OPEN".equals(fo.path("status").asText()) || "IN_PROGRESS".equals(fo.path("status").asText())) {
                lineItemsByFulfillmentOrder.add(Map.of("fulfillmentOrderId", fo.path("id").asText()));
            }
        }
        if (lineItemsByFulfillmentOrder.isEmpty()) {
            return 0;
        }

        int created = 0;
        for (OrderShipment.ShipmentPackage pkg : shipment.packages()) {
            if (pkg.trackingNumber() == null || pkg.trackingNumber().isBlank()) {
                continue;
            }
            Map<String, Object> trackingInfo = new LinkedHashMap<>();
            trackingInfo.put("number", pkg.trackingNumber());
            if (pkg.carrier() != null) {
                trackingInfo.put("company", pkg.carrier());
            }
            Map<String, Object> fulfillment = new LinkedHashMap<>();
            fulfillment.put("lineItemsByFulfillmentOrder", lineItemsByFulfillmentOrder);
            fulfillment.put("trackingInfo", trackingInfo);
            fulfillment.put("notifyCustomer", true);

            JsonNode data = gql.execute(ShopifyGraphQL.FULFILLMENT_CREATE, Map.of("fulfillment", fulfillment));
            checkUserErrors(data, "fulfillmentCreate");
            created++;
        }
        return created;
    }

    private static void checkUserErrors(JsonNode data, String op) {
        if (data == null) {
            throw new ShopifySyncException(op + ": Shopify returned no data");
        }
        JsonNode userErrors = data.path(op).path("userErrors");
        if (userErrors.isArray() && !userErrors.isEmpty()) {
            throw new ShopifySyncException(op + " userErrors: " + userErrors);
        }
    }
}
