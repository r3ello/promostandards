package com.trophy.promostandards.ordershipment.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Mirrors a focused, flattened view of the {@code OrderShipmentNotification} response: a purchase
 * order plus the packages shipped against it. The 1.0.0 schema nests packages under
 * {@code SalesOrder → ShipmentLocation → Package}; this DTO flattens them into one list, carrying
 * the sales-order number and ship-to location onto each package.
 *
 * @param purchaseOrderNumber the distributor purchase order number
 * @param complete            whether every line on the PO has shipped
 * @param packages            the shipped packages
 */
public record OrderShipment(String purchaseOrderNumber, boolean complete, List<ShipmentPackage> packages) {

	/**
	 * A single shipped package with its tracking and contents.
	 *
	 * @param salesOrderNumber  supplier sales order number
	 * @param trackingNumber    carrier tracking number
	 * @param carrier           carrier name (e.g. UPS, FedEx)
	 * @param shipmentMethod    shipment method/service level
	 * @param shipmentDate      when the package shipped
	 * @param shipToCity        ship-to city
	 * @param shipToRegion      ship-to state/region
	 * @param shipToPostalCode  ship-to postal code
	 * @param shipToCountry     ship-to country
	 * @param items             the items in the package
	 */
	public record ShipmentPackage(String salesOrderNumber, String trackingNumber, String carrier,
			String shipmentMethod, Instant shipmentDate, String shipToCity, String shipToRegion,
			String shipToPostalCode, String shipToCountry, List<ShipmentItem> items) {
	}

	/**
	 * A line item within a package.
	 *
	 * @param supplierProductId supplier product id
	 * @param supplierPartId    supplier part id
	 * @param quantity          quantity shipped
	 */
	public record ShipmentItem(String supplierProductId, String supplierPartId, BigDecimal quantity) {
	}
}
