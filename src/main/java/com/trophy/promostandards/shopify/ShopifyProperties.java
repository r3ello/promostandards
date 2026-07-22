package com.trophy.promostandards.shopify;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Shopify Admin API configuration, bound from the {@code shopify.*} keys in application.yaml.
 * All secrets are supplied via environment variables and must never be committed.
 *
 * @param storeDomain   the {@code *.myshopify.com} domain
 * @param clientId      OAuth client id (exchanged for a short-lived Admin API token)
 * @param clientSecret  OAuth client secret
 * @param webhookSecret HMAC signing secret for inbound Shopify webhooks (unused by the sync flow yet)
 * @param apiVersion    Admin API version (GraphQL), e.g. {@code 2026-04}
 * @param locationId    inventory location GID quantities are written to during inventory sync
 */
@ConfigurationProperties(prefix = "shopify")
public record ShopifyProperties(
        String storeDomain,
        String clientId,
        String clientSecret,
        String webhookSecret,
        String apiVersion,
        String locationId
) {
}
