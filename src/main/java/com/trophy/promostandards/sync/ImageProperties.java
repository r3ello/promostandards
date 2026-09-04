package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the sync does with a product's images, bound from {@code sync.images.*}.
 *
 * <p>The decision behind the defaults: <b>the supplier is the source of truth</b>. The store's
 * migrated products carry images from the old catalogue — some of them demonstrably the wrong
 * product's, from a migration bug — and the whole point of syncing is that the shop shows what
 * PaceSetter actually sells. So a product's images are replaced by the supplier's, not added to.
 *
 * <p>The one thing that is never done, whatever these say: replacing images with nothing. A supplier
 * that answers no media (PaceSetter's Media service faults outright on the GM8xx family) leaves the
 * product's images exactly as they are — see {@code CatalogService.optional}.
 *
 * @param replaceExisting  delete the product's current images before publishing the supplier's
 * @param attachToVariants point each variant at the image of its own colour, so the storefront
 *                         swaps the photo when a shopper picks a colour
 */
@ConfigurationProperties(prefix = "sync.images")
public record ImageProperties(Boolean replaceExisting, Boolean attachToVariants) {

    public boolean isReplaceExisting() {
        return replaceExisting == null || replaceExisting;
    }

    public boolean isAttachToVariants() {
        return attachToVariants == null || attachToVariants;
    }
}
