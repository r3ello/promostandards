package com.trophy.promostandards.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * Supplier → Shopify sync configuration, bound from the {@code sync.*} keys in application.yaml.
 *
 * @param supplierCode label stamped on every product and used in the deterministic Shopify handle
 * @param currency     ISO currency requested from the Pricing service
 * @param country      ISO country used for Product/Pricing localization
 * @param language     ISO language used for localization
 * @param skuStrategy  how variant SKUs are derived from supplier part ids + sizes
 * @param pricing      retail pricing rules applied to supplier net prices
 * @param schedule     cron settings for the scheduled jobs
 * @param metafields   extra product metafields stamped on every imported product
 * @param supplierMetaobject store metaobject entry identifying this supplier, referenced from every import
 */
@ConfigurationProperties(prefix = "sync")
public record SyncProperties(
        String supplierCode,
        String currency,
        String country,
        String language,
        SkuStrategy skuStrategy,
        Pricing pricing,
        Schedule schedule,
        List<Metafield> metafields,
        SupplierMetaobject supplierMetaobject
) {

    /** {@code PART} = supplier part id verbatim; {@code PART_SIZE} = {@code <partId>-<size>}. */
    public enum SkuStrategy {PART, PART_SIZE}

    /**
     * A product metafield to write on import. Provide a constant {@code value}, or a {@code source}
     * naming a supplier field to copy from. Must match the metafield definition already in Shopify
     * (namespace, key, type) for the value to stick to a defined metafield.
     *
     * @param namespace metafield namespace (e.g. {@code custom})
     * @param key       metafield key
     * @param type      Shopify metafield type (e.g. {@code single_line_text_field},
     *                  {@code list.single_line_text_field}, {@code number_integer}, {@code boolean})
     * @param value     constant value (used when {@code source} is null)
     * @param source    supplier field to copy: {@code title}, {@code description}, {@code vendor},
     *                  {@code productType}, {@code productId}, {@code tags}, {@code supplierCode}
     */
    public record Metafield(String namespace, String key, String type, String value, String source) {
    }

    /**
     * The store metaobject entry that identifies this supplier. When {@code handle} is set, every
     * import writes the {@code custom.promo_standard_supplier} product metafield
     * ({@code metaobject_reference}) pointing at this entry; its GID is resolved once via
     * {@code metaobjectByHandle} (needs the {@code read_metaobjects} scope). Blank handle = skip.
     *
     * @param type   metaobject definition type (e.g. {@code promo_standard_supplier})
     * @param handle metaobject entry handle (e.g. {@code pace-setter})
     */
    public record SupplierMetaobject(String type, String handle) {
    }

    /**
     * Retail pricing rules.
     *
     * @param strategy      pricing strategy (currently {@code MARKUP})
     * @param markupPercent percent added to the supplier net price (e.g. 40 = +40%)
     * @param rounding      price rounding applied after markup
     * @param mapFloor      when true, never price below the supplier list/MAP price
     */
    public record Pricing(Strategy strategy, BigDecimal markupPercent, Rounding rounding, boolean mapFloor) {
        public enum Strategy {MARKUP}

        public enum Rounding {NONE, NINETY_NINE}
    }

    /**
     * Scheduled-job settings.
     *
     * @param enabled       master switch; when false no scheduled job runs
     * @param inventoryCron cron for the inventory refresh job
     * @param priceCron     cron for the price refresh job
     * @param orderCron     cron for the order status/tracking job
     */
    /**
     * @param dryRun evaluate every product and log what would be pushed, without writing to Shopify.
     *               The safe way to prove the incremental logic on real data: the first pass should
     *               report everything as due, and — once run for real — the next one almost nothing.
     */
    public record Schedule(boolean enabled, String inventoryCron, String priceCron, String orderCron,
                           boolean dryRun) {
    }
}
