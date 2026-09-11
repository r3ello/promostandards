package com.trophy.promostandards.sync;

/**
 * An import was asked for a supplier id that no store product carries, and creating products is
 * off ({@link ProductCreationProperties}). Not a failure of either side: the store simply does not
 * sell it, so nothing was read from the supplier and nothing was written.
 */
public class ProductNotInStoreException extends RuntimeException {

    public ProductNotInStoreException(String productId) {
        super("No store product carries PaceSetter id " + productId
                + " (no ps-<supplier> handle, and no migrated product lists it in custom.ps_product_id"
                + " / ps_product_ids). Creating new products is disabled (sync.create-products.enabled):"
                + " only products already in Shopify are synced.");
    }
}
