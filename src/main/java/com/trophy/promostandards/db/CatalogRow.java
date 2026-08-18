package com.trophy.promostandards.db;

/**
 * One product in the catalog mirror — the cheap, catalog-wide facts the console table needs before
 * anything expensive is fetched.
 *
 * <p>Deliberately excludes the Shopify {@code imported} flag: that comes from the store, changes on
 * a different cadence, and is stamped on top when the list is served. Mixing it in here would make
 * the mirror stale in a way a rescan could not fix.
 *
 * @param productId          supplier id in the supplier's own casing (for display)
 * @param title              product name, or null when Product Data has no record
 * @param vendor             brand, normalised (supplier placeholders like "NULL" become null)
 * @param productType        most specific category
 * @param closeOut           the supplier lists it as close-out (discontinued / being sold off)
 * @param sellable           still present in {@code getProductSellable}; false = dropped out
 * @param productDataMissing listed as sellable but {@code getProduct} has no record (e.g. GI840)
 */
public record CatalogRow(String productId, String title, String vendor, String productType,
		boolean closeOut, boolean sellable, boolean productDataMissing) {

	/** Upper-cased id: the key every table joins on, since supplier casing is inconsistent. */
	public String productKey() {
		return productId.toUpperCase(java.util.Locale.ROOT);
	}
}
