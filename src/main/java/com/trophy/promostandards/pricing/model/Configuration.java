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
 * @param locations  imprint locations and the decorations valid at each — the supplier's structured
 *                   answer to "where and how big can this be decorated", which otherwise only exists
 *                   in the supplier's PDF spec sheets
 */
public record Configuration(String productId, String currency, String priceType, List<PartPrice> partPrices,
		List<Location> locations) {

	/**
	 * A decoration location on the product (e.g. "Front Center"), with the decorations it accepts.
	 *
	 * @param locationId          supplier location id
	 * @param locationName        display name of the location
	 * @param defaultLocation     whether this is the supplier's default location
	 * @param decorationsIncluded number of decorations included in the price at this location
	 * @param minDecoration       minimum decorations orderable at this location
	 * @param maxDecoration       maximum decorations orderable at this location
	 * @param decorations         the decoration methods valid here, with their imprint areas
	 */
	public record Location(int locationId, String locationName, boolean defaultLocation,
			int decorationsIncluded, int minDecoration, int maxDecoration, List<Decoration> decorations) {
	}

	/**
	 * A decoration method valid at a location, with its imprint area.
	 *
	 * @param decorationId     supplier decoration id
	 * @param decorationName   method name (e.g. "Laser Engrave", "Screen Print")
	 * @param geometry         area shape: {@code Circle}, {@code Rectangular} or {@code Other}
	 * @param height           imprint area height; null when the supplier omits it
	 * @param width            imprint area width; null when the supplier omits it
	 * @param diameter         imprint area diameter (circular areas); null otherwise
	 * @param uom              unit the area is expressed in (Inches, Stitches, Colors, …)
	 * @param defaultDecoration whether this is the supplier's default method for the location
	 */
	public record Decoration(int decorationId, String decorationName, String geometry,
			BigDecimal height, BigDecimal width, BigDecimal diameter, String uom,
			boolean defaultDecoration) {
	}

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
