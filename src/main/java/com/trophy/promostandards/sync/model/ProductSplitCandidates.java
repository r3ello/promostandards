package com.trophy.promostandards.sync.model;

import java.util.List;

/**
 * The store products a split could take apart: every one that sells several supplier ids, with what
 * becomes of each id — brought back to the product a grouping archived, or not, because none
 * remembers it.
 *
 * @param adminProducts  the admin's product URL prefix ({@code …/products/}), for links; null when unconfigured
 * @param createProducts whether an id no archived product remembers can get a product of its own
 * @param products       every non-archived store product carrying more than one supplier id
 */
public record ProductSplitCandidates(String adminProducts, boolean createProducts, List<Candidate> products) {

    /** How much of a product a split can take apart. */
    public enum Kind {
        /** every id but the product's own comes back to an archived product */
        READY,
        /** some do; the others have no product to go back to */
        PARTIAL,
        /** none does: grouped since the migration, there is nothing to restore */
        NOTHING_TO_RESTORE
    }

    /**
     * @param productId the id the product keeps — its own
     * @param adminId   the numeric product id, for the admin link
     * @param ids       every other id it carries
     */
    public record Candidate(String handle, String title, String status, String adminId, String productId,
                            Kind kind, List<Id> ids) {
    }

    /**
     * @param archivedHandle the product it goes back to, or null when no archived product remembers it
     */
    public record Id(String productId, String archivedHandle, String archivedStatus) {
    }
}
