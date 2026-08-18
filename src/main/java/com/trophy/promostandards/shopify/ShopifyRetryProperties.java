package com.trophy.promostandards.shopify;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Retry behaviour for throttled Shopify Admin API calls ({@code shopify.retry.*}).
 *
 * <p>Shopify's Admin API is rate limited by query <i>cost</i> on a leaky bucket, and it reports
 * exhaustion as a <b>200</b> response carrying {@code errors[].extensions.code = THROTTLED} — not as
 * an HTTP error. Untreated, that surfaced as a hard failure on a perfectly retryable condition.
 *
 * @param maxAttempts    total attempts per operation (1 disables retrying)
 * @param initialBackoff first wait, doubled per attempt, when the response carries no cost data
 * @param maxBackoff     ceiling for any single wait, including cost-derived ones
 */
@ConfigurationProperties(prefix = "shopify.retry")
public record ShopifyRetryProperties(
        @DefaultValue("4") int maxAttempts,
        @DefaultValue("500ms") Duration initialBackoff,
        @DefaultValue("8s") Duration maxBackoff
) {
}
