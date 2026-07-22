package com.trophy.promostandards.shopify;

/** Raised when the Shopify Admin GraphQL API returns a top-level {@code errors} array. */
public class ShopifyGraphQLException extends RuntimeException {

    public ShopifyGraphQLException(String message) {
        super(message);
    }
}
