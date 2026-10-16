package com.trophy.promostandards.sync.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Where every PromoStandards product of the store stands against PaceSetter, and what PaceSetter
 * sells that the store does not carry — the console's Status view ({@code GET /api/catalog/status}).
 *
 * @param status        {@code ready} | {@code building} (no report yet) | {@code empty} (never built)
 * @param building      a rebuild is running; when {@code status} is ready the report shown is the
 *                      previous one
 * @param builtAt       when the report shown was built, null if never
 * @param counts        products per {@link Category} name, plus {@code NEW_AT_SUPPLIER}
 * @param products      one entry per store product
 * @param newAtSupplier PaceSetter products no store product carries in any form
 * @param related       PaceSetter products the store does not list under their own code, whose
 *                      variants a store product already carries — the code that product should list
 * @param notImportable sellable PaceSetter ids no store product carries that have no product data or
 *                      no price, so could not be imported anyway
 * @param warnings      what the report could not check (e.g. Inventory unavailable)
 * @param adminProducts the store admin's products URL, to which an entry's {@code adminId} is
 *                      appended; null when the store is not configured
 */
public record CatalogStatusView(String status, boolean building, Instant builtAt, Map<String, Integer> counts,
                                List<Entry> products, List<SupplierEntry> newAtSupplier,
                                List<SupplierEntry> related, int notImportable, List<String> warnings,
                                String adminProducts) {

    /** Where a store product stands; the order is the console's. */
    public enum Category {
        /** The sync has run on it and PaceSetter still describes it. */
        SYNCED,
        /** The sync ran on it once, but PaceSetter no longer has product data or a price for it. */
        SYNCED_NO_DATA,
        /** PaceSetter has everything; it has never been synced. */
        READY,
        /** Its codes are variants of a PaceSetter product (merge), or not codes PaceSetter uses. */
        NEEDS_FIX,
        /** Its codes exist at PaceSetter, but PaceSetter has no product data or no price for them. */
        WAITING,
        /** PaceSetter has none of its codes any more. */
        DISCONTINUED,
        /** Archived in the store — most of them merged into another product. */
        ARCHIVED
    }

    /**
     * @param adminId           numeric Shopify product id, for the admin link
     * @param supplierIds       the PaceSetter codes the product lists
     * @param reason            one line: why it is in its category
     * @param syncable          a sync can be started from the console
     * @param syncBlockedReason why a {@code READY} product cannot be synced from here, null otherwise
     * @param discontinuedTag   carries the {@code pacesetter-discontinued} tag
     */
    public record Entry(Category category, String handle, String title, String status, String adminId,
                        String imageUrl, List<String> supplierIds, String reason, boolean syncable,
                        String syncBlockedReason, boolean discontinuedTag, String lastSyncAt) {
    }

    /** A PaceSetter product; {@code relatedHandles} are the store products already holding its variants. */
    public record SupplierEntry(String productId, String name, List<String> relatedHandles) {
    }
}
