package com.trophy.promostandards.vendorbridge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Reads VendorBridge ({@code util/VendorBridge.md}): what a vendor's public website says about a part.
 * JDK {@link HttpClient}, like the Shopify client, so it adds no dependency.
 *
 * <p>Read-only and never on a sync's path: the bridge's catalog is a weekly crawl, its prices are list
 * prices and a code it has not seen is searched live on the vendor's small site. Callers ask one code
 * at a time and keep the answer.
 */
@Component
public class VendorBridgeClient {

    /**
     * A part found on the vendor's site.
     *
     * @param vendorProduct the vendor's own product key (PaceSetter's page id)
     * @param matchedSku    the vendor SKU the part resolved to; null when asked by product key
     * @param matchedBy     how it was found — see {@link #needsReview()}
     * @param url           the vendor page
     */
    public record Listing(String requested, String vendorProduct, String matchedSku, String matchedBy,
                          String url, String title) {
        /** A {@code prefix} match is the longest vendor code the part starts with: maybe a neighbour. */
        public boolean needsReview() {
            return matchedBy != null && matchedBy.startsWith("prefix");
        }
    }

    private final VendorBridgeProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public VendorBridgeClient(VendorBridgeProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build();
    }

    public boolean configured() {
        return props.configured();
    }

    /**
     * The vendor's page for a part; empty when the vendor has none (the bridge's {@code PartNotFound}
     * — not in its crawl and not found by the site's own search).
     *
     * @throws VendorBridgeException anything else: no key, a refused key, the vendor failing or throttling
     */
    public Optional<Listing> product(String part) {
        if (!configured()) {
            throw new VendorBridgeException("VendorBridge is not configured (vendor-bridge.key)", false);
        }
        String base = props.baseUrl().replaceFirst("/+$", "");
        URI uri = URI.create(base + "/vendors/" + encode(props.vendor()) + "/products/" + encode(part));
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(props.requestTimeout())
                .header("Authorization", "Bearer " + props.key().trim())
                .header("Accept", "application/json").GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new VendorBridgeException("VendorBridge did not answer for " + part + ": " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VendorBridgeException("Interrupted asking VendorBridge for " + part, e);
        }
        JsonNode body = parse(response.body());
        int status = response.statusCode();
        if (status == 200) {
            JsonNode resolution = body.path("resolution");
            return Optional.of(new Listing(part, text(resolution.path("vendor_product")),
                    text(resolution.path("matched_sku")), text(resolution.path("matched_by")),
                    text(body.path("source").path("url")), text(body.path("title"))));
        }
        if (status == 404 && "PartNotFound".equals(body.path("error").asText())) {
            return Optional.empty();
        }
        String detail = body.path("detail").isValueNode() ? body.path("detail").asText() : body.path("detail").toString();
        throw new VendorBridgeException("VendorBridge answered " + status
                + (body.hasNonNull("error") ? " " + body.path("error").asText() : "")
                + (detail.isBlank() ? "" : ": " + detail) + " for " + part, status == 503 || status == 429);
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = body == null || body.isBlank() ? null : objectMapper.readTree(body);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (IOException e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node) {
        String s = node.isMissingNode() || node.isNull() ? null : node.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment.trim(), StandardCharsets.UTF_8).replace("+", "%20");
    }
}
