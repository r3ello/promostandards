package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;

/**
 * Whether an import may create a Shopify product, and what a created one looks like, bound from
 * {@code sync.create-products.*}.
 *
 * <p>Off unless switched on, because this store's catalogue is the migration's: every product it
 * sells was brought in with Matrixify, carrying its PaceSetter ids in {@code custom.ps_product_id} /
 * {@code ps_product_ids}. A PaceSetter id that none of them lists may still be a product the store
 * wants — the client asked for the 2026-09-16 batch of products new to it — but it may just as well
 * be a duplicate of one whose parts already live under other ids, so creating is a decision taken per
 * batch, not a default.
 *
 * <p>A created product is made to look like a migrated one, because from then on it is synced exactly
 * like one: handle {@code p-<number>-<name>} continuing the store's own numbering, variant SKUs
 * {@code PS<part id>} so the shelf tells a PaceSetter product at a glance, and a draft until someone
 * publishes it. Vendor, type and tags are the store's, not the supplier's: all 1,022 migrated products
 * say {@code TrophyPartner} / {@code Generic Product} / {@code promostandards} (checked 2026-09-16),
 * and a created product must not stand out from them. What the old catalogue added on top —
 * collections, category, SEO, engraving metafields — is loaded afterwards with Matrixify.
 *
 * @param enabled      let an import create a product when no store product carries the id
 * @param handlePrefix the handle's first segment ({@code p})
 * @param firstNumber  the number the first created product takes; later ones continue from the
 *                     highest the store already holds at or above it
 * @param status       the status a product is created with ({@code DRAFT}: nobody sees it before a
 *                     person has looked at it)
 * @param skuPrefix    what a variant SKU starts with when the product has no legacy number to build
 *                     it from — every product this app creates
 * @param vendor       the vendor a created product carries; blank keeps the supplier's brand
 * @param productType  the product type a created product carries; blank keeps the supplier's category
 */
@ConfigurationProperties(prefix = "sync.create-products")
public record ProductCreationProperties(Boolean enabled, String handlePrefix, Integer firstNumber,
                                        String status, String skuPrefix, String vendor,
                                        String productType) {

    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public String handlePrefixOrDefault() {
        return handlePrefix == null || handlePrefix.isBlank() ? "p" : handlePrefix.trim();
    }

    public int firstNumberOrDefault() {
        return firstNumber == null ? 10500 : firstNumber;
    }

    public String statusOrDefault() {
        return status == null || status.isBlank() ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
    }

    /** @return the configured vendor, or {@code supplier}'s when none is. */
    public String vendorOr(String supplier) {
        return vendor == null || vendor.isBlank() ? supplier : vendor.trim();
    }

    /** @return the configured product type, or {@code supplier}'s when none is. */
    public String productTypeOr(String supplier) {
        return productType == null || productType.isBlank() ? supplier : productType.trim();
    }

    /** @return the SKU prefix; an explicitly empty value means none. */
    public String skuPrefixOrDefault() {
        return skuPrefix == null ? "PS" : skuPrefix.trim();
    }
}
