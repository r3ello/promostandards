package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Caching and concurrency for the catalog read model ({@code sync.catalog.*}).
 *
 * <p>Supplier data is near-static (a product's parts, media and price breaks change daily at most),
 * but the console re-reads it constantly: paging the table, re-filtering, or simply reloading the
 * page used to replay every SOAP call. These TTLs make a product cost its upstream calls once per
 * window regardless of how many rows, tabs or users ask for it.
 *
 * @param detailTtl   how long an assembled product detail stays fresh; zero disables detail caching
 * @param listTtl     how long the discovered sellable-id list stays fresh; zero disables it
 * @param maxProducts cap on cached product details (bounds memory on a large catalog)
 * @param fetchThreads pool size for the per-product fan-out (Product/Media/Inventory/Pricing/Charges
 *                     are independent services, so they are called in parallel). Also the ceiling on
 *                     concurrent supplier calls from the catalog, so keep it supplier-friendly.
 */
@ConfigurationProperties(prefix = "sync.catalog")
public record CatalogCacheProperties(
        @DefaultValue("15m") Duration detailTtl,
        @DefaultValue("1h") Duration listTtl,
        @DefaultValue("2000") int maxProducts,
        @DefaultValue("12") int fetchThreads
) {
}
