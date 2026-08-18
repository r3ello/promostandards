package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.service.OrderShipmentService;
import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.service.OrderStatusService;
import com.trophy.promostandards.db.OrderStore;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    /** Absent unless persistence is on; absent means no dedupe, i.e. the pre-database behaviour. */
    private final ObjectProvider<OrderStore> orderStores;

    public OrderSyncService(OrderShipmentService shipments, OrderStatusService statuses,
                            ShopifyGraphQLClient gql, ObjectProvider<OrderStore> orderStores) {
        this.shipments = shipments;
        this.statuses = statuses;
        this.gql = gql;
        this.orderStores = orderStores;
    }

    /**
     * Result of an order sync run.
     *
     * @param shipmentsSkipped packages that were already fulfilled in an earlier run — the count that
     *                         shows the dedupe doing its job after a restart or an overlapping window
     */
    public record OrderSyncResult(int posProcessed, int ordersMatched, int fulfillmentsCreated,
                                  int shipmentsSkipped) {
    }

    /** Sync every supplier order updated/shipped since {@code since} onto its Shopify order. */
    public OrderSyncResult syncOrders(Instant since) {
        Map<String, String> statusByPo = latestStatusByPo(since);
        List<OrderShipment> shipped = shipments.getShippedSince(since);

        int matched = 0;
        int fulfillments = 0;
        int skipped = 0;
        for (OrderShipment shipment : shipped) {
            String poNumber = shipment.purchaseOrderNumber();
            JsonNode order = resolveOrder(poNumber);
            if (order == null) {
                log.info("No Shopify order matched supplier PO {}", poNumber);
                continue;
            }
            matched++;
            String orderGid = order.path("id").asText();
            stampStatus(orderGid, statusByPo.get(poNumber));
            FulfillmentOutcome outcome = createFulfillments(order, shipment);
            fulfillments += outcome.created();
            skipped += outcome.skipped();
        }
        OrderSyncResult result = new OrderSyncResult(shipped.size(), matched, fulfillments, skipped);
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

    /**
     * Resolves a PO to its Shopify order: by remembered id first, then by name search.
     *
     * <p>The remembered id is the reliable half. The name search is a guess about how POs are
     * recorded (see class javadoc), so once a match is confirmed it is stored and later runs stop
     * guessing — and a mapping corrected by hand in {@code order_link} is honoured from then on.
     */
    private JsonNode resolveOrder(String poNumber) {
        OrderStore store = orderStores.getIfAvailable();
        if (store != null) {
            try {
                String knownGid = store.findOrder(poNumber)
                        .map(OrderStore.OrderLink::shopifyOrderGid).orElse(null);
                if (knownGid != null) {
                    JsonNode byId = findOrderById(knownGid);
                    if (byId != null) {
                        return byId;
                    }
                    // The order was deleted or the stored id is stale: fall through and search again.
                    log.info("Stored Shopify order {} for PO {} no longer resolves; re-matching",
                            knownGid, poNumber);
                }
            } catch (RuntimeException e) {
                log.warn("Could not read the order link for PO {}: {}", poNumber, e.getMessage());
            }
        }

        JsonNode found = findOrderByName(poNumber);
        if (found != null && store != null) {
            try {
                store.saveOrder(poNumber, found.path("id").asText(), found.path("name").asText(null));
            } catch (RuntimeException e) {
                log.debug("Could not store the order link for PO {}: {}", poNumber, e.getMessage());
            }
        }
        return found;
    }

    private JsonNode findOrderById(String orderGid) {
        JsonNode data = gql.execute(ShopifyGraphQL.ORDER_BY_ID, Map.of("id", orderGid));
        if (data == null) {
            return null;
        }
        JsonNode order = data.path("order");
        return order.isMissingNode() || order.isNull() ? null : order;
    }

    private JsonNode findOrderByName(String poNumber) {
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

    /** @param created fulfillments pushed now; @param skipped packages already fulfilled earlier. */
    private record FulfillmentOutcome(int created, int skipped) {
    }

    /**
     * Turns each shipped package into a Shopify fulfillment — <b>once</b>.
     *
     * <p>Every package is checked against the shipments already pushed, because a time window is not
     * a reliable guard: the job's cursor rewinds when the app restarts, and the supplier can resend a
     * shipment inside a window that was already processed. Both cases used to create a second
     * fulfillment and email the customer a second shipping notification.
     */
    private FulfillmentOutcome createFulfillments(JsonNode order, OrderShipment shipment) {
        List<Map<String, Object>> lineItemsByFulfillmentOrder = new ArrayList<>();
        for (JsonNode fo : order.path("fulfillmentOrders").path("nodes")) {
            if ("OPEN".equals(fo.path("status").asText()) || "IN_PROGRESS".equals(fo.path("status").asText())) {
                lineItemsByFulfillmentOrder.add(Map.of("fulfillmentOrderId", fo.path("id").asText()));
            }
        }
        if (lineItemsByFulfillmentOrder.isEmpty()) {
            return new FulfillmentOutcome(0, 0);
        }

        OrderStore store = orderStores.getIfAvailable();
        String poNumber = shipment.purchaseOrderNumber();
        int created = 0;
        int skipped = 0;
        for (OrderShipment.ShipmentPackage pkg : shipment.packages()) {
            if (pkg.trackingNumber() == null || pkg.trackingNumber().isBlank()) {
                continue;
            }
            String shipmentKey = shipmentKey(pkg);
            if (store != null && store.isShipmentPushed(poNumber, shipmentKey)) {
                skipped++;
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
            if (store != null) {
                // Recorded straight after the mutation succeeds. A crash between the two would
                // duplicate on the next run; recording first would risk never fulfilling at all,
                // and a missing notification is worse than a duplicated one to reconcile.
                String fulfillmentGid = data.path("fulfillmentCreate").path("fulfillment")
                        .path("id").asText(null);
                store.recordShipmentPushed(poNumber, shipmentKey, fulfillmentGid);
            }
        }
        return new FulfillmentOutcome(created, skipped);
    }

    /**
     * Identifies a physical shipment. The tracking number is the natural key; the sales order number
     * is included because a split shipment can reuse a tracking number across sales orders.
     */
    private static String shipmentKey(OrderShipment.ShipmentPackage pkg) {
        return (pkg.salesOrderNumber() == null ? "" : pkg.salesOrderNumber().trim())
                + "|" + pkg.trackingNumber().trim();
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
