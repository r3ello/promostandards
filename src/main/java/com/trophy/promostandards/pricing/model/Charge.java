package com.trophy.promostandards.pricing.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors {@code Charge} from {@code getAvailableCharges} (e.g. setup, run, freight charges).
 *
 * @param chargeId    supplier charge id
 * @param chargeName  charge description
 * @param chargeType  charge category (e.g. "Setup", "Run", "Freight")
 * @param priceBreaks quantity price breaks for the charge
 */
public record Charge(String chargeId, String chargeName, String chargeType, List<ChargePrice> priceBreaks) {

	/**
	 * A single charge price break.
	 *
	 * @param minQuantity minimum quantity for this break
	 * @param price       net price per unit
	 * @param listPrice   list price per unit
	 * @param priceUom    unit of measure the price applies to
	 */
	public record ChargePrice(int minQuantity, BigDecimal price, BigDecimal listPrice, String priceUom) {
	}
}
