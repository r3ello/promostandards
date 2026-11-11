package com.trophy.promostandards.vendorbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client against a local HTTP server answering as VendorBridge does (util/VendorBridge.md). */
class VendorBridgeClientTest {

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(String path, int status, String body) {
        server.createContext(path, exchange -> {
            seen.add(exchange.getRequestURI().getRawPath() + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private VendorBridgeClient client(String key) {
        return new VendorBridgeClient(new VendorBridgeProperties(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/", key, "pacesetter",
                Duration.ofSeconds(2), Duration.ofSeconds(5), null, Duration.ofDays(7)), new ObjectMapper());
    }

    @Test
    void aPartOnTheSiteComesBackWithItsPageAndHowItWasMatched() {
        answer("/vendors/pacesetter/products/CM745BK", 200, """
                {"vendor":"pacesetter",
                 "resolution":{"requested":"CM745BK","vendor_product":"4265","matched_sku":"CM745BK","matched_by":"exact"},
                 "source":{"url":"https://pacesetterawards.com/product-detail/Tahoe-Insulated-Stemless-Tumbler/4265","catalog_date":null},
                 "title":"Tahoe Insulated Stemless Tumbler"}""");

        Optional<VendorBridgeClient.Listing> listing = client("secret").product("CM745BK");

        assertThat(listing).isPresent();
        assertThat(listing.get().vendorProduct()).isEqualTo("4265");
        assertThat(listing.get().title()).isEqualTo("Tahoe Insulated Stemless Tumbler");
        assertThat(listing.get().url()).endsWith("/4265");
        assertThat(listing.get().needsReview()).isFalse();
        assertThat(seen).containsExactly("/vendors/pacesetter/products/CM745BK Bearer secret");
    }

    @Test
    void aPrefixMatchIsFlaggedForReview() {
        answer("/vendors/pacesetter/products/GI582MS", 200, """
                {"resolution":{"vendor_product":"77","matched_sku":"GI582M","matched_by":"prefix"},
                 "source":{"url":"https://example.test/77"},"title":"Neighbour"}""");

        assertThat(client("secret").product("GI582MS").orElseThrow().needsReview()).isTrue();
    }

    @Test
    void partNotFoundIsAnAnswerNotAnError() {
        answer("/vendors/pacesetter/products/CD953", 404,
                "{\"vendor\":\"pacesetter\",\"error\":\"PartNotFound\",\"detail\":\"no PaceSetter product for 'CD953'\"}");

        assertThat(client("secret").product("CD953")).isEmpty();
    }

    @Test
    void aThrottleSaysSo() {
        answer("/vendors/pacesetter/products/CM1", 503,
                "{\"vendor\":\"pacesetter\",\"error\":\"VendorRefusing\",\"detail\":\"throttled\"}");

        assertThatThrownBy(() -> client("secret").product("CM1"))
                .isInstanceOfSatisfying(VendorBridgeException.class, e -> assertThat(e.throttled()).isTrue())
                .hasMessageContaining("VendorRefusing");
    }

    @Test
    void aRefusedKeyIsAnErrorThatIsNotAThrottle() {
        answer("/vendors/pacesetter/products/CM1", 401, "{\"detail\":\"an API key is required\"}");

        assertThatThrownBy(() -> client("wrong").product("CM1"))
                .isInstanceOfSatisfying(VendorBridgeException.class, e -> assertThat(e.throttled()).isFalse())
                .hasMessageContaining("401").hasMessageContaining("an API key is required");
    }

    @Test
    void withoutAKeyNothingIsCalled() {
        VendorBridgeClient client = client(" ");

        assertThat(client.configured()).isFalse();
        assertThatThrownBy(() -> client.product("CM1")).isInstanceOf(VendorBridgeException.class);
        assertThat(seen).isEmpty();
    }
}
