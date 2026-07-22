package com.trophy.promostandards.media.model;

/**
 * Mirrors the 1.1.0 {@code MediaDateModified}: a product/part whose media changed since the
 * requested timestamp. (1.1.0 returns only the identifiers, not a per-item change date.)
 *
 * @param productId supplier product id
 * @param partId    supplier part id (may be null)
 */
public record MediaDateModified(String productId, String partId) {
}
