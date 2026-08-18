package com.trophy.promostandards.sync.model;

import java.time.Instant;
import java.util.List;

/**
 * Response for the catalog title index: every product id's name and vendor, so the console can
 * filter the table by product name locally instead of asking the server per keystroke.
 *
 * @param status  {@code ready} (data available), {@code building} (a first build is running, no data
 *                yet), or {@code empty} (disabled / never built)
 * @param builtAt when the current index was built; null when never built
 * @param count   number of titles returned
 * @param titles  one entry per product id the supplier has a record for
 */
public record CatalogTitleView(String status, Instant builtAt, int count, List<CatalogTitle> titles) {

    /**
     * @param productId supplier product id
     * @param title     marketing name
     * @param vendor    brand/vendor, normalised (supplier placeholders like "NULL" become null)
     */
    public record CatalogTitle(String productId, String title, String vendor) {
    }
}
