package com.trophy.promostandards.vendorbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trophy.promostandards.sync.model.CatalogStatusView.Web;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** What the website check asks, what it remembers and when it gives up. */
class VendorWebIndexTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final List<String> asked = new ArrayList<>();

    private VendorBridgeProperties props(String key) {
        return new VendorBridgeProperties("http://unused.test", key, "pacesetter", Duration.ofSeconds(1),
                Duration.ofSeconds(1), dir.resolve("listings.json").toString(), Duration.ofDays(7));
    }

    /** An index whose bridge answers from {@code answers} instead of the network. */
    private VendorWebIndex index(String key, Function<String, Optional<VendorBridgeClient.Listing>> answers) {
        VendorBridgeProperties props = props(key);
        VendorBridgeClient client = new VendorBridgeClient(props, mapper) {
            @Override
            public Optional<VendorBridgeClient.Listing> product(String part) {
                asked.add(part);
                return answers.apply(part);
            }
        };
        VendorWebIndex index = new VendorWebIndex(client, props, mapper);
        index.load();
        return index;
    }

    private static Optional<VendorBridgeClient.Listing> page(String part, String matchedBy) {
        return Optional.of(new VendorBridgeClient.Listing(part, "4265", part, matchedBy,
                "https://pacesetterawards.com/product-detail/x/4265", "Tahoe"));
    }

    @Test
    void theFirstCodeFoundAnswersForTheProductAndTheRestAreNotAsked() {
        VendorWebIndex index = index("k", part -> part.equals("CM745BK") ? page(part, "exact") : Optional.empty());

        String stopped = index.check(List.of(List.of("CM999", "cm745bk", "CM745BL"), List.of("CD1")));

        assertThat(stopped).isNull();
        assertThat(asked).containsExactly("CM999", "CM745BK", "CD1");
        Map<String, Web> known = index.known(List.of("CM999", "CM745BK", "CM745BL", "CD1"));
        assertThat(known.keySet()).containsExactly("CD1", "CM745BK", "CM999");
        assertThat(known.get("CM745BK").found()).isTrue();
        assertThat(known.get("CM745BK").url()).endsWith("/4265");
        assertThat(known.get("CM999").found()).isFalse();
    }

    @Test
    void aFamilyCodeIsAskedWithoutItsWildcard() {
        VendorWebIndex index = index("k", part -> page(part, "exact"));

        index.check(List.of(List.of("CM745*")));

        assertThat(asked).containsExactly("CM745");
        assertThat(index.known(List.of("cm745*"))).containsKey("CM745");
    }

    @Test
    void anAnswerIsRememberedAcrossPassesAndRestarts() {
        index("k", part -> Optional.empty()).check(List.of(List.of("CM1"), List.of("CM2")));
        asked.clear();

        VendorWebIndex restarted = index("k", part -> page(part, "exact"));
        restarted.check(List.of(List.of("CM1"), List.of("CM2"), List.of("CM3")));

        assertThat(asked).containsExactly("CM3");
        assertThat(restarted.known(List.of("CM1")).get("CM1").found()).isFalse();
    }

    @Test
    void aThrottleEndsThePassAndKeepsWhatItLearned() {
        VendorWebIndex index = index("k", part -> {
            if (part.equals("CM2")) {
                throw new VendorBridgeException("VendorBridge answered 503 VendorRefusing", true);
            }
            return page(part, "exact");
        });

        String stopped = index.check(List.of(List.of("CM1"), List.of("CM2"), List.of("CM3")));

        assertThat(stopped).contains("throttling");
        assertThat(asked).containsExactly("CM1", "CM2");
        assertThat(index.known(List.of("CM1", "CM2", "CM3")).keySet()).containsExactly("CM1");
    }

    @Test
    void oneFailureIsSkippedButThreeInARowEndThePass() {
        VendorWebIndex index = index("k", part -> {
            if (part.startsWith("BAD")) {
                throw new VendorBridgeException("VendorBridge answered 502 VendorFailed", false);
            }
            return page(part, "exact");
        });

        assertThat(index.check(List.of(List.of("BAD1"), List.of("CM1")))).isNull();
        assertThat(index.known(List.of("BAD1", "CM1")).keySet()).containsExactly("CM1");

        asked.clear();
        String stopped = index.check(List.of(List.of("BAD1"), List.of("BAD2"), List.of("BAD3"), List.of("CM4")));
        assertThat(stopped).contains("not answering");
        assertThat(asked).containsExactly("BAD1", "BAD2", "BAD3");
    }

    @Test
    void aPrefixMatchIsKeptAsNeedingReview() {
        VendorWebIndex index = index("k", part -> page("GI582M", "prefix"));

        index.check(List.of(List.of("GI582MS")));

        assertThat(index.known(List.of("GI582MS")).get("GI582MS").needsReview()).isTrue();
    }

    @Test
    void withoutAKeyItIsOffAndAsksNothing() {
        VendorWebIndex index = index("", part -> page(part, "exact"));

        assertThat(index.enabled()).isFalse();
        assertThat(index.check(List.of(List.of("CM1")))).isNull();
        assertThat(asked).isEmpty();
    }
}
