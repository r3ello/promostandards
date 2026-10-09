package com.trophy.promostandards.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks the supplier's web server whether an image it lists actually exists.
 *
 * <p>PaceSetter's Media service names files its own site does not serve: every one of CD902Y*'s
 * fourteen entries is {@code CD902Y50.jpg}, a 404. Shopify accepts such a URL, fails to fetch it
 * afterwards and keeps the media as {@code FAILED} — a broken tile on the product (2026-10-08,
 * CD902Y* and CD901Y*). So a URL is dropped before publishing, but only on an answer that says the
 * file is gone (404, 410). Anything else — a timeout, a 5xx, a server refusing HEAD — keeps it: a
 * flaky supplier must never be what strips a product of its photos.
 */
class ImageUrlProbe {

    private static final Logger log = LoggerFactory.getLogger(ImageUrlProbe.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    boolean exists(String url) {
        try {
            int status = status(url, "HEAD");
            if (status == 405 || status == 501) {
                status = status(url, "GET");    // a server that does not do HEAD
            }
            if (status == 404 || status == 410) {
                log.info("Supplier image {} answers {}; not publishing it", url, status);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        } catch (Exception e) {
            log.debug("Could not check supplier image {}; publishing it anyway: {}", url, e.getMessage());
            return true;
        }
    }

    private int status(String url, String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
