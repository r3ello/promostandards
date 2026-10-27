package com.trophy.promostandards.sync.model;

import java.util.List;

/**
 * What splitting a grouped store product back into one product per supplier id would do — computed
 * before anything is written.
 *
 * <p>The reverse of {@link ProductGroupPreview}. A grouping archived the products whose ids it took
 * and left a note on each ({@code trophy_sync.grouped_into} / {@code grouped_ids}); splitting brings
 * those products back with their ids, and takes the ids and their variants off the grouped product.
 *
 * @param productId       the supplier id the grouped product keeps — its own, canonical one
 * @param handle          the grouped product
 * @param keptIds         what its {@code custom.ps_product_ids} will list afterwards
 * @param split           one entry per supplier id that leaves it
 * @param removedVariants the grouped product's variants that go with them (their SKUs)
 * @param conflicts       reasons this split would not be safe to apply as asked
 * @param warnings        what is worth knowing but does not stop it
 * @param applicable      whether the split is free of conflicts
 */
public record ProductUngroupPreview(String productId, String handle, List<String> keptIds,
                                    List<Split> split, List<String> removedVariants,
                                    List<String> conflicts, List<String> warnings, boolean applicable) {

    /** What becomes of the store products the ids go to. */
    public enum Action {
        /** an archived product the grouping absorbed comes back, with its ids */
        RESTORE,
        /** no store product remembers the id: the sync creates one, as a draft */
        CREATE
    }

    /**
     * One supplier id leaving the grouped product.
     *
     * @param handle       the store product it goes back to; null when one is created
     * @param status       that product's status now (ARCHIVED, usually); null when one is created
     * @param supplierIds  every id that product will carry — a grouping can absorb a product that
     *                     carried several, and they come back together
     */
    public record Split(String productId, Action action, String handle, String status,
                        List<String> supplierIds) {
    }
}
