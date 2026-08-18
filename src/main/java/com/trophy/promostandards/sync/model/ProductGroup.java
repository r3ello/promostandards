package com.trophy.promostandards.sync.model;

import java.util.List;

/**
 * A family of supplier product ids that are really one product split by the supplier across several
 * ids (e.g. one per size), discovered from {@code Common Grouping} related-product links.
 *
 * @param primaryId the representative id (lowest member id, stable across rebuilds)
 * @param memberIds all product ids in the family, including the primary (size &gt;= 2)
 */
public record ProductGroup(String primaryId, List<String> memberIds) {
}
