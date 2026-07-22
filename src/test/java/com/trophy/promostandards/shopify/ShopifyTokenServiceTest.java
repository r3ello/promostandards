package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ShopifyTokenServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ShopifyProperties props() {
        return new ShopifyProperties("shop.myshopify.com", "client-id", "client-secret",
                "secret", "2026-04", "gid://shopify/Location/1");
    }

    @Test
    void fetchesAndCachesToken() {
        AtomicInteger calls = new AtomicInteger();
        ShopifyHttp http = (path, body, headers) -> {
            calls.incrementAndGet();
            assertThat(path).isEqualTo("/admin/oauth/access_token");
            try {
                return MAPPER.readTree("{\"access_token\":\"abc123\",\"expires_in\":3600}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifyTokenService service = new ShopifyTokenService(http, props());

        assertThat(service.getToken()).isEqualTo("abc123");
        assertThat(service.getToken()).isEqualTo("abc123");
        assertThat(calls.get()).isEqualTo(1); // second call served from cache
    }

    @Test
    void failsWhenTokenMissing() {
        ShopifyHttp http = (path, body, headers) -> {
            try {
                return MAPPER.readTree("{}");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifyTokenService service = new ShopifyTokenService(http, props());

        try {
            service.getToken();
            assertThat(false).as("expected IllegalStateException").isTrue();
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessageContaining("access_token");
        }
    }
}
