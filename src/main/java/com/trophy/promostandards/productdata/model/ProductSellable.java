package com.trophy.promostandards.productdata.model;

/**
 * Mirrors {@code ProductSellable}: a product/part returned for the requested sellable filter.
 *
 * <p>1.0.0 returns only the identifiers of items matching the request's {@code isSellable} flag, so
 * {@code sellable} echoes that requested filter value.
 *
 * @param productId supplier product id
 * @param partId    supplier part id (may be null)
 * @param sellable  the requested sellable filter these items matched
 */
public record ProductSellable(String productId, String partId, boolean sellable) {
}
