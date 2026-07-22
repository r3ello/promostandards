package com.trophy.promostandards.ordershipment.client;

import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.model.OrderShipmentRequests.GetOrderShipmentNotificationRequest;

import java.util.List;

/**
 * Client for the PromoStandards Order Shipment Notification Service (1.0.0).
 *
 * <p>Mirrors the SOAP operation so {@link StubOrderShipmentClient} can be swapped for the real
 * SOAP-backed {@link SoapOrderShipmentClient} via {@code promostandards.order-shipment.mode=soap}.
 */
public interface OrderShipmentClient {

	/** {@code getOrderShipmentNotification} — shipments (tracking + contents) for an order or ship date. */
	List<OrderShipment> getOrderShipmentNotification(GetOrderShipmentNotificationRequest request);
}
