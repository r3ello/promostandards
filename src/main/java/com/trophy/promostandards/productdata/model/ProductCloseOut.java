package com.trophy.promostandards.productdata.model;

/**
 * Mirrors {@code ProductCloseOut}: a product/part flagged as close-out (being discontinued).
 *
 * @param productId supplier product id
 * @param partId    supplier part id
 */
public record ProductCloseOut(String productId, String partId) {
}
