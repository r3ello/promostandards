package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** Retry fast in tests: the throttling backoff is behaviour, not something to wait out. */
    private static final ShopifyRetryProperties TEST_RETRY =
            new ShopifyRetryProperties(3, java.time.Duration.ofMillis(1), java.time.Duration.ofMillis(5));

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
        return new ShopifyGraphQLClient(http, tokenService, props, TEST_RETRY);
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

    /** Serves each response in turn, so a throttled attempt can be followed by a successful one. */
    private ShopifyGraphQLClient clientReturningInOrder(AtomicInteger calls, List<String> responses) {
        ShopifyHttp http = (path, body, headers) -> {
            int i = calls.getAndIncrement();
            try {
                return MAPPER.readTree(responses.get(Math.min(i, responses.size() - 1)));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifyTokenService tokenService = mock(ShopifyTokenService.class);
        when(tokenService.getToken()).thenReturn("test-token");
        ShopifyProperties props = new ShopifyProperties(
                "shop.myshopify.com", "client-id", "client-secret", "secret", "2026-04", "gid://shopify/Location/1");
        return new ShopifyGraphQLClient(http, tokenService, props, TEST_RETRY);
    }

    /**
     * Shopify reports a spent rate-limit bucket as HTTP 200 carrying {@code extensions.code:
     * THROTTLED}. That is a wait-and-repeat condition, not a failed operation — it used to abort the
     * caller (an import, or a whole page of catalog rows).
     */
    @Test
    void retriesAThrottledResponseAndSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        ShopifyGraphQLClient client = clientReturningInOrder(calls, List.of(
                """
                {"errors":[{"message":"Throttled","extensions":{"code":"THROTTLED"}}],
                 "extensions":{"cost":{"requestedQueryCost":100,
                   "throttleStatus":{"maximumAvailable":1000,"currentlyAvailable":99,"restoreRate":50}}}}""",
                "{\"data\":{\"products\":{\"nodes\":[]}}}"));

        JsonNode data = client.execute("query { products { nodes { id } } }", Map.of());

        assertThat(data.path("products").path("nodes")).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }

    /** Retrying is bounded: a store that stays throttled still surfaces the error to the caller. */
    @Test
    void givesUpAfterMaxAttempts() {
        AtomicInteger calls = new AtomicInteger();
        ShopifyGraphQLClient client = clientReturningInOrder(calls, List.of(
                "{\"errors\":[{\"message\":\"Throttled\",\"extensions\":{\"code\":\"THROTTLED\"}}]}"));

        assertThatThrownBy(() -> client.execute("query { x }", Map.of()))
                .isInstanceOf(ShopifyGraphQLException.class)
                .hasMessageContaining("THROTTLED");
        assertThat(calls.get()).isEqualTo(3);   // TEST_RETRY.maxAttempts()
    }

    /** A non-throttling error is a real failure: fail on the first response, don't hammer the store. */
    @Test
    void doesNotRetryNonThrottlingErrors() {
        AtomicInteger calls = new AtomicInteger();
        ShopifyGraphQLClient client = clientReturningInOrder(calls, List.of(
                "{\"errors\":[{\"message\":\"Field 'nope' doesn't exist\"}]}"));

        assertThatThrownBy(() -> client.execute("query { nope }", Map.of()))
                .isInstanceOf(ShopifyGraphQLException.class);
        assertThat(calls.get()).isEqualTo(1);
    }
}
