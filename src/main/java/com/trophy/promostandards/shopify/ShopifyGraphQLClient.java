package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;

/**
 * Executes a single GraphQL operation against the Shopify Admin API and returns its {@code data}
 * node. The access token is short-lived, so it is resolved per request from
 * {@link ShopifyTokenService}. Top-level GraphQL {@code errors} are surfaced as a
 * {@link ShopifyGraphQLException}; per-mutation {@code userErrors} are left for callers to inspect
 * on the returned node.
 */
@Component
public class ShopifyGraphQLClient {

    static final String ACCESS_TOKEN_HEADER = "X-Shopify-Access-Token";

    private static final Logger log = LoggerFactory.getLogger(ShopifyGraphQLClient.class);

    private final ShopifyHttp http;
    private final ShopifyTokenService tokenService;
    private final ShopifyRetryProperties retry;
    private final String graphqlPath;

    public ShopifyGraphQLClient(ShopifyHttp http,
                                ShopifyTokenService tokenService,
                                ShopifyProperties props,
                                ShopifyRetryProperties retry) {
        this.http = http;
        this.tokenService = tokenService;
        this.retry = retry;
        this.graphqlPath = "/admin/api/" + props.apiVersion() + "/graphql.json";
    }

    /**
     * Executes the operation, retrying while Shopify reports the rate-limit bucket as exhausted.
     *
     * @param query     the GraphQL query or mutation
     * @param variables the operation variables
     * @return the {@code data} node of the response, or {@code null} if absent
     */
    public JsonNode execute(String query, Map<String, Object> variables) {
        int attempts = Math.max(1, retry.maxAttempts());
        for (int attempt = 1; ; attempt++) {
            JsonNode response = http.postJson(graphqlPath,
                    Map.of("query", query, "variables", variables),
                    Map.of(ACCESS_TOKEN_HEADER, tokenService.getToken()));

            if (response != null && response.hasNonNull("errors") && !response.get("errors").isEmpty()) {
                if (isThrottled(response) && attempt < attempts) {
                    Duration wait = backoff(response, attempt);
                    log.debug("Shopify throttled the request; retrying in {}ms (attempt {}/{})",
                            wait.toMillis(), attempt + 1, attempts);
                    sleep(wait);
                    continue;
                }
                throw new ShopifyGraphQLException(response.get("errors").toString());
            }
            return response == null ? null : response.get("data");
        }
    }

    /** Rate-limit exhaustion arrives as HTTP 200 with {@code extensions.code = THROTTLED}. */
    private static boolean isThrottled(JsonNode response) {
        for (JsonNode error : response.get("errors")) {
            if ("THROTTLED".equalsIgnoreCase(error.path("extensions").path("code").asText(null))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prefers Shopify's own cost data — how long the leaky bucket needs to hold this query's cost —
     * and falls back to exponential backoff when the response carries none.
     */
    private Duration backoff(JsonNode response, int attempt) {
        JsonNode cost = response.path("extensions").path("cost");
        JsonNode throttleStatus = cost.path("throttleStatus");
        double requested = cost.path("requestedQueryCost").asDouble(0);
        double available = throttleStatus.path("currentlyAvailable").asDouble(0);
        double restoreRate = throttleStatus.path("restoreRate").asDouble(0);

        Duration wait;
        if (requested > available && restoreRate > 0) {
            wait = Duration.ofMillis((long) Math.ceil((requested - available) / restoreRate * 1000));
        } else {
            wait = retry.initialBackoff().multipliedBy(1L << (attempt - 1));
        }
        return wait.compareTo(retry.maxBackoff()) > 0 ? retry.maxBackoff() : wait;
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(0, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShopifyGraphQLException("Interrupted while waiting out a Shopify rate limit");
        }
    }
}
