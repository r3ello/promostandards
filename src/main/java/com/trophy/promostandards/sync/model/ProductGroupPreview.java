package com.trophy.promostandards.sync.model;

import java.util.List;

/**
 * What selling several supplier products as one store product would produce — computed before
 * anything is written.
 *
 * <p>The store already models this: a product's {@code custom.ps_product_ids} lists every supplier
 * id it covers, and the sync unions them into one variant set. What has been missing is a way to
 * decide it from the app instead of a Matrixify sheet, and a way to see the result first. Grouping
 * is easy to undo on paper and hard in Shopify — the sync never deletes a variant, so a group made
 * by mistake leaves its variants behind — which is why this answers before it acts.
 *
 * @param variants   what the grouped product would carry, in the order the store would show them
 * @param absorbed   store products whose ids would move to the parent, leaving them with none
 * @param conflicts  reasons this grouping would not be safe to apply as asked
 * @param warnings   what the supplier could not answer for while building the preview
 * @param applicable whether the grouping is free of conflicts
 */
public record ProductGroupPreview(String parentProductId, String parentHandle,
                                  List<String> supplierIds, List<Variant> variants,
                                  List<Absorbed> absorbed, List<String> conflicts,
                                  List<String> warnings, boolean applicable) {

    /**
     * One variant of the grouped product.
     *
     * @param color the Color option value as the store would show it — already disambiguated, so a
     *              colour two supplier products share reads "Dark Brown (BL)" here too
     */
    public record Variant(String supplierProductId, String partId, String color, String size,
                          Integer onHand) {
    }

    /** A store product the grouping would empty: its ids move to the parent. */
    public record Absorbed(String handle, List<String> supplierIds, List<String> notRequested) {
    }
}
