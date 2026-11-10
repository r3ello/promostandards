package com.trophy.promostandards.db;

import java.time.Instant;
import java.util.List;

/**
 * A record of which store product carries which supplier id — {@code shopify_product_link}.
 *
 * <p><b>Never the source of truth, and never read by the sync.</b> The store's metafields
 * ({@code custom.ps_product_id} / {@code ps_product_ids}) are what every lookup goes by. This is
 * written from the store index each time a complete listing rebuilds it, so the relation can be
 * looked up afterwards — by GID, which survives a handle rename — even after the metafield that
 * stated it has been edited or deleted in the store.
 *
 * <p>Absent unless {@code sync.persistence.enabled=true}; callers hold it as an
 * {@code ObjectProvider} and a failure to write it must never fail what triggered it.
 */
public interface ProductLinkStore {

	/**
	 * One (supplier id, store product) pair as the store listed it.
	 *
	 * @param canonical the id is the product's {@code custom.ps_product_id}
	 */
	record Link(String productId, String shopifyGid, String handle, String source, boolean canonical) {
	}

	/**
	 * Records a <b>complete</b> listing of the store: every pair in {@code links} is marked current
	 * (seen at {@code seenAt}), and every pair currently linked but absent from it is marked unlinked.
	 * Never call it with a partial listing — absence is read as "the product no longer carries it".
	 * An empty list is ignored, so a store that answered nothing cannot unlink everything.
	 *
	 * @return the number of pairs that became unlinked
	 */
	int recordListing(List<Link> links, Instant seenAt);

	/** A row of {@code shopify_product_link}; {@code unlinkedAt} null while still linked. */
	record LinkRow(String productId, String shopifyGid, String handle, String source, boolean canonical,
	               Instant firstSeenAt, Instant lastSeenAt, Instant unlinkedAt) {
	}

	/** Every store product that has ever carried {@code productId}, current ones first. */
	List<LinkRow> history(String productId);
}
