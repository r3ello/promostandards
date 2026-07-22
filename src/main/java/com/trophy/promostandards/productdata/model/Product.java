package com.trophy.promostandards.productdata.model;

import java.util.List;

/**
 * Mirrors a (focused subset of) the PromoStandards {@code Product} response type.
 *
 * @param productId    supplier product id
 * @param productName  marketing name
 * @param description  product description
 * @param productBrand brand name
 * @param categories   category names the product belongs to
 * @param parts        the product's parts
 */
public record Product(String productId, String productName, String description, String productBrand,
		List<String> categories, List<ProductPart> parts) {

	/**
	 * Mirrors {@code ProductPart} (subset).
	 *
	 * @param partId          supplier part id
	 * @param description      part description
	 * @param primaryColor     primary color name
	 * @param sizes            available size labels
	 */
	public record ProductPart(String partId, String description, String primaryColor, List<String> sizes) {
	}
}
