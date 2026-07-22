package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

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

    private final ShopifyHttp http;
    private final ShopifyTokenService tokenService;
    private final String graphqlPath;

    public ShopifyGraphQLClient(ShopifyHttp http,
                                ShopifyTokenService tokenService,
                                ShopifyProperties props) {
        this.http = http;
        this.tokenService = tokenService;
        this.graphqlPath = "/admin/api/" + props.apiVersion() + "/graphql.json";
    }

    /**
     * @param query     the GraphQL query or mutation
     * @param variables the operation variables
     * @return the {@code data} node of the response, or {@code null} if absent
     */
    public JsonNode execute(String query, Map<String, Object> variables) {
        JsonNode response = http.postJson(graphqlPath,
                Map.of("query", query, "variables", variables),
                Map.of(ACCESS_TOKEN_HEADER, tokenService.getToken()));

        if (response != null && response.hasNonNull("errors") && !response.get("errors").isEmpty()) {
            throw new ShopifyGraphQLException(response.get("errors").toString());
        }
        return response == null ? null : response.get("data");
    }
}
