package com.trophy.promostandards.vendorbridge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * VendorBridge, the team's own service that reads the vendors' <em>public websites</em> — a second
 * source next to PromoStandards. Bound from {@code vendor-bridge.*}; see {@code util/VendorBridge.md}.
 *
 * @param baseUrl        the service's root
 * @param key            this app's bearer key ({@code VENDORBRIDGE_KEY}); blank = the bridge is off and
 *                       nothing ever calls it
 * @param vendor         the vendor id asked about
 * @param connectTimeout how long to wait for the connection
 * @param requestTimeout how long one answer may take; a code the bridge has never seen is looked up
 *                       live on the vendor's site, which takes seconds
 * @param cacheFile      where the website answers are kept across restarts
 * @param ttl            how long an answer is trusted; the bridge re-crawls weekly, so asking more
 *                       often only repeats the same answer
 */
@ConfigurationProperties(prefix = "vendor-bridge")
public record VendorBridgeProperties(
        @DefaultValue("https://vendorbridge.yoordy.com") String baseUrl,
        String key,
        @DefaultValue("pacesetter") String vendor,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("60s") Duration requestTimeout,
        @DefaultValue("data/vendor-bridge-listings.json") String cacheFile,
        @DefaultValue("7d") Duration ttl
) {
    public boolean configured() {
        return key != null && !key.isBlank() && baseUrl != null && !baseUrl.isBlank();
    }
}
