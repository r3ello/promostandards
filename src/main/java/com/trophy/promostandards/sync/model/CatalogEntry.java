package com.trophy.promostandards.sync.model;

/**
 * A lightweight catalog list entry — just enough to render a table row immediately. Per-product
 * detail (variants, inventory, pricing, media) is fetched lazily per visible row via the detail
 * endpoint, so listing the catalog stays a single cheap upstream call instead of aggregating every
 * product up front.
 *
 * @param productId supplier product id
 * @param imported  whether the product exists in Shopify; null when Shopify isn't connected
 */
public record CatalogEntry(String productId, Boolean imported) {
}
