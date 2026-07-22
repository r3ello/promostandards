package com.trophy.promostandards.orderstatus.model;

import java.time.Instant;

/**
 * Request payloads for the Order Status Service (1.0.0). Credentials and {@code wsVersion} are
 * supplied by the service layer from configuration.
 */
public final class OrderStatusRequests {

	private OrderStatusRequests() {
	}

	/**
	 * Mirrors {@code GetOrderStatusDetailsRequest}.
	 *
	 * @param queryType       1 = by purchase order, 2 = by sales order, 3 = last update, 4 = all open
	 * @param referenceNumber PO/SO number (for queryType 1/2)
	 * @param statusTimeStamp earliest status-change timestamp (for queryType 3)
	 */
	public record GetOrderStatusDetailsRequest(String wsVersion, String id, String password, int queryType,
			String referenceNumber, Instant statusTimeStamp) {
	}

	/** Mirrors {@code GetOrderStatusTypesRequest}. */
	public record GetOrderStatusTypesRequest(String wsVersion, String id, String password) {
	}
}
