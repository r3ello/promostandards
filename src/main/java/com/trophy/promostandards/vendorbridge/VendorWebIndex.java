package com.trophy.promostandards.vendorbridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.sync.model.CatalogStatusView.Web;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Is this code still on PaceSetter's website?", remembered. The Status view asks it about the
 * products PromoStandards no longer describes, where the answer separates a product PaceSetter
 * dropped from one its web service merely stopped serving.
 *
 * <p>Every answer — found or not — is kept for {@link VendorBridgeProperties#ttl()} and on disk, so
 * a rebuild asks only for what it does not know. Codes are asked <b>one at a time</b>: one the bridge
 * has never seen costs a live search on PaceSetter's site, which throttles for hours when pushed. A
 * throttle, or three failures in a row, ends the pass; the next one carries on from the cache.
 */
@Service
public class VendorWebIndex {

    private static final Logger log = LoggerFactory.getLogger(VendorWebIndex.class);
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int PERSIST_EVERY = 25;

    private final VendorBridgeClient client;
    private final VendorBridgeProperties props;
    private final ObjectMapper objectMapper;
    private final Map<String, Web> cache = new ConcurrentHashMap<>();

    public VendorWebIndex(VendorBridgeClient client, VendorBridgeProperties props, ObjectMapper objectMapper) {
        this.client = client;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public boolean enabled() {
        return client.configured();
    }

    @PostConstruct
    void load() {
        Path file = cacheFile();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            cache.putAll(objectMapper.readValue(file.toFile(), new TypeReference<Map<String, Web>>() {
            }));
            log.info("Loaded {} PaceSetter website answers from {}", cache.size(), file);
        } catch (IOException e) {
            log.warn("Could not read the website answers {}: {}", file, e.getMessage());
        }
    }

    /** The answers still trusted for these codes, keyed as {@link #key} spells them; asks nothing. */
    public Map<String, Web> known(Collection<String> codes) {
        Map<String, Web> out = new TreeMap<>();
        for (String code : codes) {
            Web web = fresh(key(code));
            if (web != null) {
                out.put(key(code), web);
            }
        }
        return out;
    }

    /**
     * Asks the bridge for what is not known yet. Each element is one store product's codes, in order:
     * the first one found answers for the product and the rest are not asked.
     *
     * @return what kept the pass from finishing, for the report's warnings; null when it finished
     */
    public String check(List<List<String>> products) {
        if (!enabled()) {
            return null;
        }
        int asked = 0;
        int failures = 0;
        try {
            for (List<String> codes : products) {
                for (String code : codes) {
                    String key = key(code);
                    if (key.isEmpty()) {
                        continue;
                    }
                    Web web = fresh(key);
                    if (web == null) {
                        try {
                            web = ask(key);
                            cache.put(key, web);
                            failures = 0;
                            if (++asked % PERSIST_EVERY == 0) {
                                persist();
                            }
                        } catch (VendorBridgeException e) {
                            log.warn("Website check: {}", e.getMessage());
                            if (e.throttled() || ++failures >= MAX_CONSECUTIVE_FAILURES) {
                                return "The check against PaceSetter's website stopped early ("
                                        + (e.throttled() ? "the site is throttling" : "VendorBridge is not answering")
                                        + "): some products show no website answer. The next rebuild carries on.";
                            }
                            continue;
                        }
                    }
                    if (web.found()) {
                        break;
                    }
                }
            }
            return null;
        } finally {
            if (asked > 0) {
                persist();
                log.info("Website check asked VendorBridge about {} codes", asked);
            }
        }
    }

    private Web ask(String key) {
        Optional<VendorBridgeClient.Listing> listing = client.product(key);
        return listing.map(l -> new Web(true, l.url(), l.title(), l.matchedSku(), l.matchedBy(), l.needsReview(),
                Instant.now())).orElseGet(() -> new Web(false, null, null, null, null, false, Instant.now()));
    }

    private Web fresh(String key) {
        Web web = cache.get(key);
        if (web == null || web.checkedAt() == null) {
            return null;
        }
        Duration ttl = props.ttl();
        boolean expired = ttl != null && !ttl.isZero() && !ttl.isNegative()
                && web.checkedAt().plus(ttl).isBefore(Instant.now());
        return expired ? null : web;
    }

    /** As the bridge is asked: upper case, and a family's wildcard dropped (CM745* is page CM745). */
    public static String key(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceFirst("\\*+$", "");
    }

    private Path cacheFile() {
        return props.cacheFile() == null || props.cacheFile().isBlank() ? null : Path.of(props.cacheFile());
    }

    private synchronized void persist() {
        Path file = cacheFile();
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            objectMapper.writeValue(file.toFile(), new LinkedHashMap<>(new TreeMap<>(cache)));
        } catch (IOException e) {
            log.warn("Could not write the website answers {}: {}", file, e.getMessage());
        }
    }
}
