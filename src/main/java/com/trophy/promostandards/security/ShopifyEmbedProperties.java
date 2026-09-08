package com.trophy.promostandards.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Whether this console is served <b>inside the Shopify admin</b> ({@code shopify.embedded.*}).
 *
 * <p>Turning it on changes three things, all additive: the console's HTML gains the App Bridge
 * script (so the browser can mint session tokens), every response declares the
 * {@code frame-ancestors} the Shopify admin needs to frame us, and {@link SessionAuthFilter} starts
 * accepting a Shopify session token as an alternative to the form-login cookie.
 *
 * <p>That last point is the whole reason this exists: inside the admin the app runs in an iframe on
 * {@code admin.shopify.com}, so its own {@code PS_SESSION} cookie is a third-party cookie and the
 * browser never sends it back — the login screen appears to work and every subsequent call is a 401.
 * Shopify's answer is the session token, and it is the only one that survives third-party cookie
 * blocking.
 *
 * <p>The credentials come from {@code shopify.client-id} / {@code shopify.client-secret} (the same
 * app that mints the Admin API token) and the shop from {@code shopify.store-domain}, so there is
 * normally nothing else to configure.
 *
 * @param enabled     master switch; off by default so a local run neither loads App Bridge nor
 *                    accepts bearer tokens
 * @param shopDomains <b>further</b> {@code *.myshopify.com} hosts that are the same store. Shopify
 *                    gives a store a randomly generated permanent domain
 *                    ({@code wy2ena-jf.myshopify.com}) as well as the one built from its name, and
 *                    <b>the session token always carries the permanent one</b> — while the Admin API
 *                    answers on either, which is why a store can be synced for weeks through a
 *                    domain its own tokens never mention. List the other one here rather than
 *                    changing {@code shopify.store-domain}, which is what every sync call uses.
 */
@ConfigurationProperties(prefix = "shopify.embedded")
public record ShopifyEmbedProperties(@DefaultValue("false") boolean enabled, List<String> shopDomains) {

	public ShopifyEmbedProperties {
		shopDomains = shopDomains == null ? List.of() : List.copyOf(shopDomains);
	}
}
