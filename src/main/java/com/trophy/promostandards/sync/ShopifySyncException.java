package com.trophy.promostandards.sync;

/**
 * Raised when a Shopify sync mutation completes the request but reports {@code userErrors} (e.g. an
 * invalid option value or SKU conflict), as opposed to a transport/top-level GraphQL failure.
 */
public class ShopifySyncException extends RuntimeException {

    public ShopifySyncException(String message) {
        super(message);
    }
}
