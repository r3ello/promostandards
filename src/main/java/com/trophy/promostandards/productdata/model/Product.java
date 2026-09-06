package com.trophy.promostandards.productdata.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Mirrors a (focused subset of) the PromoStandards {@code Product} response type.
 *
 * @param productId       supplier product id
 * @param productName     marketing name
 * @param description     product description
 * @param productBrand    brand name
 * @param categories      category names the product belongs to
 * @param parts           the product's parts
 * @param relatedProducts links to other products (e.g. {@code Common Grouping} siblings); never null
 */
public record Product(String productId, String productName, String description, String productBrand,
		List<String> categories, List<ProductPart> parts, List<RelatedProduct> relatedProducts) {

	public Product {
		if (relatedProducts == null) {
			relatedProducts = List.of();
		}
	}

	/** Convenience for callers that don't populate related products (stubs, tests, older mappings). */
	public Product(String productId, String productName, String description, String productBrand,
			List<String> categories, List<ProductPart> parts) {
		this(productId, productName, description, productBrand, categories, parts, List.of());
	}

	/**
	 * Mirrors {@code ProductPart} (subset).
	 *
	 * @param partId          supplier part id
	 * @param description      part description
	 * @param primaryColor     primary color name
	 * @param sizes            available size labels
	 * @param weight           shipping weight of one unit, from the part's {@code Dimension} block;
	 *                         null when the supplier gives none. It is the only shipping figure worth
	 *                         mapping: the dimensions in that same block are what PaceSetter already
	 *                         repeats as the inventory row's size attribute ("9.25 X 7"), while the
	 *                         weight appears nowhere else and is what a store needs to quote postage
	 * @param weightUom        unit the weight is expressed in (PaceSetter: {@code LB})
	 */
	public record ProductPart(String partId, String description, String primaryColor, List<String> sizes,
			BigDecimal weight, String weightUom) {
	}

	/**
	 * Mirrors {@code RelatedProduct}: a typed link from this product to another one.
	 *
	 * @param relationType one of {@code Substitute}, {@code Companion Sell}, {@code Common Grouping}
	 * @param productId    the related product's id
	 * @param partId       the related part id (optional; may be null)
	 */
	public record RelatedProduct(String relationType, String productId, String partId) {
	}
}
