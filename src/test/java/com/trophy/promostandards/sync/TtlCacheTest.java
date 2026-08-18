package com.trophy.promostandards.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtlCacheTest {

    @Test
    void memoisesUntilTheEntryExpires() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMillis(120), 10);

        assertThat(cache.get("k", k -> loads.incrementAndGet())).isEqualTo(1);
        assertThat(cache.get("k", k -> loads.incrementAndGet())).isEqualTo(1);

        Thread.sleep(200);
        assertThat(cache.get("k", k -> loads.incrementAndGet())).isEqualTo(2);
    }

    /**
     * The point of the cache for this app: a page of console rows asking at once must produce one
     * upstream call, not one per caller.
     */
    @Test
    void concurrentCallersForOneKeyShareASingleLoad() throws Exception {
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        TtlCache<String, Integer> cache = new TtlCache<>(Duration.ofMinutes(5), 10);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    start.await();
                    return cache.get("k", k -> {
                        loads.incrementAndGet();
                        sleep(50);   // a slow supplier call
                        return 1;
                    });
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(loads.get()).isEqualTo(1);
    }

    /** A transient supplier failure must not be pinned for the whole TTL. */
    @Test
    void doesNotCacheFailures() {
        AtomicInteger attempts = new AtomicInteger();
        TtlCache<String, String> cache = new TtlCache<>(Duration.ofMinutes(5), 10);

        assertThatThrownBy(() -> cache.get("k", k -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("upstream down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(cache.get("k", k -> "recovered")).isEqualTo("recovered");
        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    void zeroTtlDisablesCaching() {
        AtomicInteger loads = new AtomicInteger();
        TtlCache<String, Integer> cache = new TtlCache<>(Duration.ZERO, 10);

        cache.get("k", k -> loads.incrementAndGet());
        cache.get("k", k -> loads.incrementAndGet());

        assertThat(loads.get()).isEqualTo(2);
    }

    /** The cache loader is a plain Function, so a checked sleep needs unwrapping. */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void evictsOldestPastTheSizeCap() {
        TtlCache<Integer, Integer> cache = new TtlCache<>(Duration.ofMinutes(5), 2);
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 3; i++) {
            cache.get(i, k -> loads.incrementAndGet());
        }
        cache.get(0, k -> loads.incrementAndGet());   // evicted -> reloaded
        cache.get(2, k -> loads.incrementAndGet());   // still cached

        assertThat(loads.get()).isEqualTo(4);
    }
}
