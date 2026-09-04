package com.trophy.promostandards.sync.model;

/**
 * A lightweight catalog list entry — just enough to render a table row immediately. Per-product
 * detail (variants, inventory, pricing, media) is fetched lazily per visible row via the detail
 * endpoint, so listing the catalog stays a single cheap upstream call instead of aggregating every
 * product up front.
 *
 * @param productId supplier product id
 * @param imported  whether the product exists in Shopify; null when Shopify isn't connected
 * @param closeOut  whether the supplier lists this product as close-out (being discontinued /
 *                  sold off), per {@code getProductCloseOut} — the supplier's own signal, unlike
 *                  {@code productDataMissing}, which only says its Product Data service has no record
 * @param discountsPublished whether the store product carries the quantity-break ladder metafield.
 *                  Comes free with the imported flag (same cached store index), so the table can
 *                  show at a glance which products carry discounts
 */
public record CatalogEntry(String productId, Boolean imported, boolean closeOut,
                           boolean discountsPublished) {
}
