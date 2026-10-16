package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The console's catalog status report, bound from {@code sync.catalog-status.*}.
 *
 * @param enabled   off = {@code GET /api/catalog/status} answers {@code empty} and nothing is read
 * @param cacheFile where the last report is kept, so a restart does not cost a rebuild
 * @param ttl       how old a report may be before a view starts a rebuild; it costs one price read
 *                  per sellable id (~1,300), so once a day is plenty
 */
@ConfigurationProperties(prefix = "sync.catalog-status")
public record CatalogStatusProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("data/catalog-status.json") String cacheFile,
        @DefaultValue("24h") Duration ttl
) {
}
