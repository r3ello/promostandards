package com.trophy.promostandards.sync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Only an answer that says the file is gone drops an image: CD902Y50.jpg (404 on PaceSetter's own
 * site) must not reach Shopify, but a server error or a refused HEAD must never cost a photo.
 */
class ImageUrlProbeTest {

    private HttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok.jpg", x -> { x.sendResponseHeaders(200, -1); x.close(); });
        server.createContext("/gone.jpg", x -> { x.sendResponseHeaders(404, -1); x.close(); });
        server.createContext("/removed.jpg", x -> { x.sendResponseHeaders(410, -1); x.close(); });
        server.createContext("/broken.jpg", x -> { x.sendResponseHeaders(500, -1); x.close(); });
        server.createContext("/no-head.jpg", x -> {
            x.sendResponseHeaders("HEAD".equals(x.getRequestMethod()) ? 405 : 200, -1);
            x.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void dropsOnlyWhatTheServerSaysIsGone() {
        ImageUrlProbe probe = new ImageUrlProbe();

        assertThat(probe.exists(base + "/ok.jpg")).isTrue();
        assertThat(probe.exists(base + "/gone.jpg")).isFalse();
        assertThat(probe.exists(base + "/removed.jpg")).isFalse();
        assertThat(probe.exists(base + "/broken.jpg")).isTrue();
        assertThat(probe.exists(base + "/no-head.jpg")).isTrue();
        // Nothing listening: unknown, so kept.
        assertThat(probe.exists("http://127.0.0.1:1/nothing.jpg")).isTrue();
    }
}
