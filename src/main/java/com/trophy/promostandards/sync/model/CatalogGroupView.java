package com.trophy.promostandards.sync.model;

import java.time.Instant;
import java.util.List;

/**
 * Response for the catalog group index: the (cached) mapping of product families used to collapse
 * sibling rows in the console table. Only multi-member families are returned; any product id not
 * listed here is standalone.
 *
 * @param status       {@code ready} (data available), {@code building} (a first build is running,
 *                     no data yet), or {@code empty} (grouping disabled / never built)
 * @param builtAt      when the current index was built; null when never built
 * @param productCount number of product ids covered by the returned families
 * @param groupCount   number of families
 * @param groups       the families (each with a primary id and its member ids)
 */
public record CatalogGroupView(String status, Instant builtAt, int productCount, int groupCount,
                               List<ProductGroup> groups) {
}
