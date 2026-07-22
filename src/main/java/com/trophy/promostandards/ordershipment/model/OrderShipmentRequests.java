package com.trophy.promostandards.ordershipment.model;

import java.time.Instant;

/**
 * Request payloads for the Order Shipment Notification Service (1.0.0). Credentials and
 * {@code wsVersion} are supplied by the service layer from configuration.
 */
public final class OrderShipmentRequests {

	private OrderShipmentRequests() {
	}

	/**
	 * Mirrors {@code GetOrderShipmentNotificationRequest}.
	 *
	 * @param queryType             1 = by purchase order, 2 = by sales order, 3 = by ship date
	 * @param referenceNumber       PO/SO number (required for queryType 1/2)
	 * @param shipmentDateTimeStamp earliest ship date (required for queryType 3)
	 */
	public record GetOrderShipmentNotificationRequest(String wsVersion, String id, String password, int queryType,
			String referenceNumber, Instant shipmentDateTimeStamp) {
	}
}
