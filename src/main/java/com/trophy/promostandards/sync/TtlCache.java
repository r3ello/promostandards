package com.trophy.promostandards.sync;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A small time-expiring memo cache with per-key single-flight loading.
 *
 * <p>Hand-rolled on purpose: the Shopify client already avoids WebFlux to keep the build dependency
 * free and offline-buildable, and Spring's own {@code ConcurrentMapCacheManager} has no expiry
 * (Caffeine would mean a new dependency). This covers what the catalog needs and nothing more.
 *
 * <p>Two properties matter for the supplier traffic this guards:
 * <ul>
 *   <li><b>Single-flight per key</b>: the loader runs under a per-key lock, so five console rows
 *       asking for the same product at once produce one upstream call, not five. The lock is held
 *       on a slot object, never on a map bin, so unrelated keys never block each other.</li>
 *   <li><b>Failures are not cached</b>: a transient supplier error must not stick for the whole TTL.
 *       The exception propagates to every waiter and the next call retries.</li>
 * </ul>
 *
 * <p>Size is bounded: past {@code maxEntries} the expired entries go first, then the oldest.
 */
final class TtlCache<K, V> {

    private final Duration ttl;
    private final int maxEntries;
    private final Map<K, Slot<V>> slots = new ConcurrentHashMap<>();

    TtlCache(Duration ttl, int maxEntries) {
        this.ttl = ttl == null ? Duration.ZERO : ttl;
        this.maxEntries = Math.max(1, maxEntries);
    }

    private static final class Slot<V> {
        volatile V value;
        volatile Instant loadedAt;
    }

    /** @return the cached value, or the loader's result (memoised) when missing or stale. */
    V get(K key, Function<K, V> loader) {
        if (ttl.isZero() || ttl.isNegative()) {
            return loader.apply(key);   // caching disabled
        }
        Slot<V> slot = slots.computeIfAbsent(key, k -> new Slot<>());  // fast: no I/O under the map
        if (isFresh(slot)) {
            return slot.value;
        }
        synchronized (slot) {
            if (isFresh(slot)) {
                return slot.value;      // another thread loaded it while we waited
            }
            V value = loader.apply(key);
            slot.value = value;
            slot.loadedAt = Instant.now();
            evictIfOverCapacity();
            return value;
        }
    }

    /** Drop a single key (e.g. its underlying data just changed). */
    void invalidate(K key) {
        slots.remove(key);
    }

    void clear() {
        slots.clear();
    }

    private boolean isFresh(Slot<V> slot) {
        Instant loadedAt = slot.loadedAt;
        return loadedAt != null && loadedAt.plus(ttl).isAfter(Instant.now());
    }

    /** Expired entries first, then oldest-loaded, until back at capacity. */
    private void evictIfOverCapacity() {
        if (slots.size() <= maxEntries) {
            return;
        }
        List<Map.Entry<K, Slot<V>>> live = new ArrayList<>();
        for (Map.Entry<K, Slot<V>> entry : slots.entrySet()) {
            if (isFresh(entry.getValue())) {
                live.add(entry);
            } else {
                slots.remove(entry.getKey(), entry.getValue());
            }
        }
        if (live.size() <= maxEntries) {
            return;
        }
        live.sort(Comparator.comparing(e -> e.getValue().loadedAt));
        for (int i = 0; i < live.size() - maxEntries; i++) {
            slots.remove(live.get(i).getKey(), live.get(i).getValue());
        }
    }
}
