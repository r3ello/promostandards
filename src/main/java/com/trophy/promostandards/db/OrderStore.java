package com.trophy.promostandards.db;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for order syncing: which Shopify order a supplier PO belongs to, which shipments
 * have already been turned into fulfillments, and how far the order job has got.
 *
 * <p>{@code order_shipment_pushed} is the one piece of state in this system whose loss has a
 * customer-visible cost. Everything else rebuilds itself; forgetting that a shipment was already
 * fulfilled means Shopify creates a second fulfillment and emails the customer a second shipping
 * notification.
 *
 * <p>Absent unless {@code sync.persistence.enabled=true}. Without it the service behaves as it did
 * before — which is exactly the behaviour that produces those duplicates on restart, so enabling
 * persistence is the fix, not an optimisation.
 */
public interface OrderStore {

	/** @param shopifyOrderGid null when the PO could not be matched to any order. */
	record OrderLink(String poNumber, String shopifyOrderGid, String shopifyOrderName) {
	}

	Optional<OrderLink> findOrder(String poNumber);

	/** Remembers a PO → order match so later runs resolve it by id instead of re-searching by name. */
	void saveOrder(String poNumber, String shopifyOrderGid, String shopifyOrderName);

	/**
	 * @return true when this exact shipment has already been fulfilled in Shopify.
	 * @param shipmentKey identifies the physical shipment (tracking number + package), not the run
	 */
	boolean isShipmentPushed(String poNumber, String shipmentKey);

	/** Records a created fulfillment so the same shipment is never fulfilled twice. */
	void recordShipmentPushed(String poNumber, String shipmentKey, String fulfillmentGid);

	/** @return how far a job has processed, or empty when it has never run. */
	Optional<Instant> watermark(String job);

	/** Advances a job's watermark. Only ever called after a successful run. */
	void saveWatermark(String job, Instant cursorAt);
}
