package com.trophy.promostandards.productdata.model;

/**
 * Mirrors the 1.0.0 {@code ProductDateModified}: a product/part that changed since the requested
 * timestamp. (1.0.0 returns only the identifiers, not a per-item change date.)
 *
 * @param productId supplier product id
 * @param partId    supplier part id (may be null)
 */
public record ProductDateModified(String productId, String partId) {
}
