package com.trophy.promostandards.sync;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Downloads the spreadsheet Trophy Options uploads for a large order ({@code _trophy_items.csv}).
 *
 * <p>Only from {@code https://cdn.shopify.com}: the URL is a line property, which the shopper's browser
 * writes, so any other host would have this server fetch whatever a shopper typed. The file is
 * versioned in its URL ({@code ?v=…}) and never changes under it, so it is kept an hour — the pending
 * and sent lists render every order's preview, and would otherwise download each file on every load.
 * A failed download is not kept (see {@link TtlCache}).
 */
@Component
public class TrophyCsv {

    static final String HOST = "cdn.shopify.com";
    private static final int MAX_BYTES = 10 * 1024 * 1024;   // 500 pieces × 6 lines is ~50 KB

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final TtlCache<String, String> cache = new TtlCache<>(Duration.ofHours(1), 200);

    /** @throws IllegalArgumentException naming why the file could not be had */
    public String fetch(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("its CSV link is not a URL: " + url);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !HOST.equalsIgnoreCase(uri.getHost())) {
            throw new IllegalArgumentException("its CSV is not on " + HOST + " (" + url + ")");
        }
        return cache.get(url, u -> download(uri));
    }

    private String download(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalArgumentException("its CSV could not be downloaded (HTTP "
                            + response.statusCode() + " from " + uri + ")");
                }
                byte[] bytes = in.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) {
                    throw new IllegalArgumentException("its CSV is over " + MAX_BYTES / (1024 * 1024) + " MB");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("its CSV could not be downloaded (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("its CSV download was interrupted", e);
        }
    }
}
