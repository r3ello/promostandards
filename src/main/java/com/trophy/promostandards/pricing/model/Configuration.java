package com.trophy.promostandards.pricing.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors a focused subset of the {@code Configuration} response from
 * {@code getConfigurationAndPricing}.
 *
 * @param productId  supplier product id
 * @param currency   ISO currency code prices are expressed in
 * @param priceType  "Net" or "List"
 * @param partPrices price breaks per part
 */
public record Configuration(String productId, String currency, String priceType, List<PartPrice> partPrices) {

	/**
	 * Price breaks for a single part.
	 *
	 * @param partId      supplier part id
	 * @param description part description
	 * @param priceBreaks quantity price breaks, ascending by {@code minQuantity}
	 */
	public record PartPrice(String partId, String description, List<PriceBreak> priceBreaks) {
	}

	/**
	 * A single quantity price break.
	 *
	 * @param minQuantity minimum quantity for this break
	 * @param price       net/list price per unit (per the request's {@code priceType})
	 * @param listPrice   published list price per unit
	 * @param priceUom    unit of measure the price applies to
	 */
	public record PriceBreak(int minQuantity, BigDecimal price, BigDecimal listPrice, String priceUom) {
	}
}
