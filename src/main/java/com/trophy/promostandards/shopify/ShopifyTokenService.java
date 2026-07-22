package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Obtains and caches a short-lived Admin API access token via the OAuth {@code client_credentials}
 * grant. Shopify returns a token that expires in up to ~24h; this service reuses the cached token
 * while valid and transparently fetches a new one once it nears expiry.
 *
 * <p>Thread-safe: reads are lock-free on the volatile reference; refreshes are serialized so a
 * burst of concurrent requests triggers at most one token call.
 */
@Component
public class ShopifyTokenService {

    private static final Logger log = LoggerFactory.getLogger(ShopifyTokenService.class);

    private static final String TOKEN_PATH = "/admin/oauth/access_token";
    /** Refresh this far ahead of the reported expiry to avoid using a token that expires mid-flight. */
    private static final Duration EXPIRY_MARGIN = Duration.ofMinutes(2);
    /** Fallback lifetime if the response omits {@code expires_in}. */
    private static final long DEFAULT_EXPIRES_IN_SECONDS = 600;

    private final ShopifyHttp http;
    private final ShopifyProperties props;

    private final Object refreshLock = new Object();
    private volatile CachedToken cached;

    public ShopifyTokenService(ShopifyHttp http, ShopifyProperties props) {
        this.http = http;
        this.props = props;
    }

    /** @return a currently-valid Admin API access token, fetching a fresh one if needed. */
    public String getToken() {
        CachedToken current = cached;
        if (current != null && current.isValid()) {
            return current.token();
        }
        synchronized (refreshLock) {
            // Re-check: another thread may have refreshed while we waited on the lock.
            if (cached != null && cached.isValid()) {
                return cached.token();
            }
            cached = requestToken();
            return cached.token();
        }
    }

    private CachedToken requestToken() {
        log.debug("Requesting new Shopify Admin API token via client_credentials grant");
        JsonNode response = http.postJson(TOKEN_PATH, Map.of(
                "client_id", props.clientId(),
                "client_secret", props.clientSecret(),
                "grant_type", "client_credentials"), Map.of());

        String token = response == null ? null : response.path("access_token").asText(null);
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("client_credentials response missing access_token: " + response);
        }

        long expiresIn = response.path("expires_in").asLong(DEFAULT_EXPIRES_IN_SECONDS);
        Instant expiresAt = Instant.now().plusSeconds(expiresIn).minus(EXPIRY_MARGIN);
        log.info("Obtained Shopify Admin API token; valid for ~{}s (refresh after {})", expiresIn, expiresAt);
        return new CachedToken(token, expiresAt);
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return Instant.now().isBefore(expiresAt);
        }
    }
}
