package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the catalog "group variants" index ({@code sync.group-index.*}). The index maps
 * sibling supplier product ids (one product the supplier split across ids) into families via
 * {@code Common Grouping} related products, so the console table can collapse them into one row.
 *
 * <p>Building it costs one {@code getProduct} per product, so it is built on demand (never at
 * startup), cached in memory, and persisted to {@code cacheFile} so a restart doesn't repeat the
 * heavy pass.
 *
 * @param enabled   master switch; when false the grouped view stays empty (flat list only)
 * @param cacheFile path of the JSON cache persisted across restarts (relative to the working dir)
 * @param ttl       how long a built index is considered fresh before a background rebuild; zero
 *                  disables staleness (rebuild only on explicit refresh)
 */
@ConfigurationProperties(prefix = "sync.group-index")
public record CatalogGroupProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("data/catalog-group-index.json") String cacheFile,
        @DefaultValue("24h") Duration ttl
) {
}
