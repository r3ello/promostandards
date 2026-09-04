package com.trophy.promostandards.discount;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where a product's quantity-break ladder is published: one Shopify metafield holding the whole
 * ladder as JSON, bound from the {@code discounts.*} keys in application.yaml.
 *
 * <p>The store's discount app reads that metafield — there is no API to call, no token to capture
 * and nothing that can be half-written, which is the whole point of doing it this way. Namespace and
 * key are configuration because the app that consumes them names them, and that name is the only
 * thing that has to match.
 *
 * @param enabled   master switch; when false the discount endpoints report as disabled and write
 *                  nothing (defaults to true)
 * @param namespace metafield namespace, on both the product and every covered variant
 *                  ({@code trophy_discount}: a merchant-owned namespace we are allowed to write)
 * @param key       metafield key ({@code discount_tiers})
 * @param type      Shopify metafield type — {@code json} unless the consuming app defined it
 *                  otherwise (a {@code multi_line_text_field} holding the same string also works)
 */
@ConfigurationProperties(prefix = "discounts")
public record DiscountProperties(Boolean enabled, String namespace, String key, String type) {

    /**
     * A <b>merchant-owned</b> namespace, which is the whole point: the discount app's own
     * {@code app--400283500545} is a reserved namespace and Shopify refuses this app's writes into it
     * ("Access to this namespace and key on Metafields for this resource type is not allowed",
     * verified live on CM373BS, 2026-09-03). {@code trophy_discount} belongs to the store, so we can
     * write it and the storefront app is pointed at it.
     */
    private static final String DEFAULT_NAMESPACE = "trophy_discount";
    private static final String DEFAULT_KEY = "discount_tiers";

    /** Not {@code enabled()}: a record accessor cannot narrow {@code Boolean} to {@code boolean}. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public String namespace() {
        return blank(namespace) ? DEFAULT_NAMESPACE : namespace.trim();
    }

    public String key() {
        return blank(key) ? DEFAULT_KEY : key.trim();
    }

    public String type() {
        return blank(type) ? "json" : type.trim();
    }

    /** {@code namespace.key}, the form a person recognises from the Shopify admin. */
    public String qualifiedName() {
        return namespace() + "." + key();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
