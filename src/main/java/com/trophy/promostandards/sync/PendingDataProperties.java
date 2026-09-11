package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the pending-data index ({@code sync.pending-data.*}) — the catalog ids whose
 * Inventory service answers nothing, which the console keeps out of "Not imported" because they are
 * not ready to import.
 *
 * @param enabled   master switch; when false no id is ever reported as pending
 * @param cacheFile path of the JSON cache persisted across restarts (relative to the working dir)
 * @param ttl       how long a built index is fresh before a background rebuild; zero disables
 *                  staleness (rebuild only on explicit refresh)
 */
@ConfigurationProperties(prefix = "sync.pending-data")
public record PendingDataProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("data/pending-data.json") String cacheFile,
        @DefaultValue("6h") Duration ttl
) {
}
