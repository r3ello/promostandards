package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Whether an import may create a Shopify product, bound from {@code sync.create-products.*}.
 *
 * <p>Off unless switched on, because this store's catalogue is the migration's: every product it
 * sells was brought in with Matrixify, carrying its PaceSetter ids in {@code custom.ps_product_id} /
 * {@code ps_product_ids}. The sync keeps those products up to date; a PaceSetter id that none of them
 * lists is not something the store sells, and importing it would publish a product nobody chose —
 * or, when its parts already live in a migrated product under other ids, a duplicate of one.
 *
 * @param enabled let an import create {@code ps-<supplier>-<id>} when no store product carries the id
 */
@ConfigurationProperties(prefix = "sync.create-products")
public record ProductCreationProperties(Boolean enabled) {

    public boolean isEnabled() {
        return enabled != null && enabled;
    }
}
