package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stubs the {@link ShopifyHttp} transport with a fake, so no live HTTP server or external mock
 * library is needed.
 */
class ShopifyGraphQLClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Captured(String path, Object body, Map<String, String> headers) {
    }

    private final AtomicReference<Captured> last = new AtomicReference<>();

    private ShopifyGraphQLClient clientReturning(String responseJson) {
        ShopifyHttp http = (path, body, headers) -> {
            last.set(new Captured(path, body, headers));
            try {
                return MAPPER.readTree(responseJson);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifyTokenService tokenService = mock(ShopifyTokenService.class);
        when(tokenService.getToken()).thenReturn("test-token");
        ShopifyProperties props = new ShopifyProperties(
                "shop.myshopify.com", "client-id", "client-secret", "secret", "2026-04", "gid://shopify/Location/1");
        return new ShopifyGraphQLClient(http, tokenService, props);
    }

    @Test
    void sendsQueryAndVariablesAndReturnsDataNode() {
        ShopifyGraphQLClient client = clientReturning(
                "{\"data\":{\"product\":{\"id\":\"gid://shopify/Product/1\"}}}");

        JsonNode data = client.execute("query Q($id: ID!){ product(id:$id){ id } }",
                Map.of("id", "gid://shopify/Product/1"));

        assertThat(data.path("product").path("id").asText())
                .isEqualTo("gid://shopify/Product/1");

        assertThat(last.get().path()).isEqualTo("/admin/api/2026-04/graphql.json");
        assertThat(last.get().headers()).containsEntry("X-Shopify-Access-Token", "test-token");
        assertThat(MAPPER.valueToTree(last.get().body()).toString())
                .contains("query").contains("variables").contains("gid://shopify/Product/1");
    }

    @Test
    void throwsOnTopLevelErrors() {
        ShopifyGraphQLClient client = clientReturning("{\"errors\":[{\"message\":\"Throttled\"}]}");

        assertThatThrownBy(() -> client.execute("query { x }", Map.of()))
                .isInstanceOf(ShopifyGraphQLException.class)
                .hasMessageContaining("Throttled");
    }
}
