package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configuration for the catalog title index ({@code sync.title-index.*}) — the product name/vendor
 * of every catalog id, so the console's search box can match on names instead of only on ids.
 *
 * <p>It rides on the shared {@link SupplierProductScan}, so when the group index is also enabled the
 * two cost one catalog pass between them, not two.
 *
 * @param enabled   master switch; when false the search box matches ids (and loaded rows) only
 * @param cacheFile path of the JSON cache persisted across restarts (relative to the working dir)
 * @param ttl       how long a built index is fresh before a background rebuild; zero disables
 *                  staleness (rebuild only on explicit refresh)
 */
@ConfigurationProperties(prefix = "sync.title-index")
public record CatalogTitleProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("data/catalog-titles.json") String cacheFile,
        @DefaultValue("24h") Duration ttl
) {
}
