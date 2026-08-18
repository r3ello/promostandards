package com.trophy.promostandards.db;

import com.trophy.promostandards.sync.model.ProductGroup;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the catalog mirror: the supplier's product list and the variant families
 * derived from it.
 *
 * <p>The mirror is never authoritative. PaceSetter owns the catalog and Shopify owns identity;
 * losing this data costs one rescan and nothing else. That is what lets the read path fall back to
 * live upstream calls whenever the database is missing or unreachable, instead of failing.
 *
 * <p>A bean exists only when {@code sync.persistence.enabled=true}, so callers inject it as
 * {@code ObjectProvider<CatalogStore>} and treat absence as "no mirror, use the supplier".
 */
public interface CatalogStore {

	/**
	 * Replaces the catalog mirror with a freshly scanned set.
	 *
	 * <p>Products absent from {@code rows} are <b>marked unsellable, never deleted</b>: an id that
	 * drops out of the supplier's feed may still exist in Shopify, and deleting the row would take
	 * its link and sync bookkeeping with it.
	 *
	 * @param rows every product seen in this scan
	 * @return number of rows written
	 */
	int saveCatalog(List<CatalogRow> rows);

	/** @return every mirrored product, sellable first, in catalog order. */
	List<CatalogRow> findAll();

	/** @return one page of the mirror, filtered and sorted in SQL. */
	CatalogQuery.Page search(CatalogQuery.Request request);

	/** @return when the mirror was last refreshed, or empty when it has never been populated. */
	Optional<Instant> lastScanAt();

	/** Replaces the stored variant families wholesale (they are recomputed as a set, not merged). */
	void saveGroups(List<ProductGroup> groups);

	/** @return the stored variant families, empty when none have been computed yet. */
	List<ProductGroup> findGroups();
}
