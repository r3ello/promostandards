package com.trophy.promostandards.orderstatus.model;

import java.time.Instant;
import java.util.List;

/**
 * Mirrors a focused subset of the {@code OrderStatus} response: a purchase order plus its status
 * detail entries.
 *
 * @param purchaseOrderNumber the distributor purchase order number
 * @param details             the status detail entries for the order
 */
public record OrderStatus(String purchaseOrderNumber, List<OrderStatusDetail> details) {

	/**
	 * Mirrors {@code OrderStatusDetail}.
	 *
	 * @param factoryOrderNumber    supplier/factory order number
	 * @param statusId              numeric status code (see {@code getOrderStatusTypes})
	 * @param statusName            status name
	 * @param expectedShipDate      expected ship date (may be null)
	 * @param expectedDeliveryDate  expected delivery date (may be null)
	 * @param additionalExplanation free-text explanation (may be null)
	 * @param responseRequired      whether the supplier requires a response
	 * @param validTimestamp        when this status was valid
	 */
	public record OrderStatusDetail(String factoryOrderNumber, int statusId, String statusName,
			Instant expectedShipDate, Instant expectedDeliveryDate, String additionalExplanation,
			boolean responseRequired, Instant validTimestamp) {
	}
}
