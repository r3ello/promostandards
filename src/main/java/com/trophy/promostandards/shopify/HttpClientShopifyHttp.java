package com.trophy.promostandards.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * {@link ShopifyHttp} backed by the JDK {@link HttpClient} (no third-party HTTP dependency). The
 * base URL is {@code https://<store-domain>}; the token endpoint and the Admin GraphQL endpoint are
 * both reached through it.
 */
@Component
public class HttpClientShopifyHttp implements ShopifyHttp {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public HttpClientShopifyHttp(ObjectMapper objectMapper, ShopifyProperties props) {
        this.objectMapper = objectMapper;
        this.baseUrl = "https://" + props.storeDomain();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public JsonNode postJson(String path, Object body, Map<String, String> extraHeaders) {
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(body);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not serialize Shopify request body", e);
        }

        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        extraHeaders.forEach(request::header);

        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new ShopifyGraphQLException("Shopify request to " + path + " failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShopifyGraphQLException("Shopify request to " + path + " was interrupted");
        }

        if (response.statusCode() >= 400) {
            throw new ShopifyGraphQLException(
                    "Shopify returned HTTP " + response.statusCode() + " for " + path
                            + ": " + new String(response.body()));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new ShopifyGraphQLException("Shopify response from " + path + " was not valid JSON");
        }
    }
}
