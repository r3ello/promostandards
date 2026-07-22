package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Minimal JSON-over-HTTP transport to the Shopify store host. Abstracted behind an interface so the
 * GraphQL client and token service stay transport-agnostic and are trivially testable with a fake
 * (see tests) — no live HTTP server or external mock library needed.
 */
public interface ShopifyHttp {

    /**
     * POSTs {@code body} as JSON to {@code path} on the configured store host.
     *
     * @param path         request path beginning with {@code /} (e.g. {@code /admin/api/.../graphql.json})
     * @param body         object serialized to a JSON request body
     * @param extraHeaders headers to add beyond {@code Content-Type: application/json}
     * @return the parsed JSON response body
     */
    JsonNode postJson(String path, Object body, Map<String, String> extraHeaders);
}
