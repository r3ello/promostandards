package com.trophy.promostandards.productdata.model;

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
	 */
	public record ProductPart(String partId, String description, String primaryColor, List<String> sizes) {
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
