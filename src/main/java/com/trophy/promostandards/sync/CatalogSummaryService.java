package com.trophy.promostandards.sync;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.PromoStandardsNotFoundException;
import com.trophy.promostandards.db.CatalogQuery;
import com.trophy.promostandards.db.CatalogRow;
import com.trophy.promostandards.db.CatalogStore;
import com.trophy.promostandards.inventory.service.InventoryService;
import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.service.MediaService;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.service.PricingService;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.model.CatalogEntry;
import com.trophy.promostandards.sync.model.ProductDetail;
import com.trophy.promostandards.sync.model.ProductDetail.ChargeRow;
import com.trophy.promostandards.sync.model.ProductDetail.DecorationArea;
import com.trophy.promostandards.sync.model.ProductDetail.DecorationLocation;
import com.trophy.promostandards.sync.model.ProductDetail.InventoryRow;
import com.trophy.promostandards.sync.model.ProductDetail.PriceBreak;
import com.trophy.promostandards.sync.model.ProductDetail.PricePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Read model behind the catalog table.
 *
 * <p>The list is intentionally <b>cheap</b>: it discovers product ids from the supplier's sellable
 * catalog (a single upstream call) plus one Shopify "imported" lookup — it does <b>not</b> aggregate
 * every product. {@link #detail(String)} (called lazily per visible/expanded row) assembles the full
 * per-service detail for display: the raw inventory variations, the quantity price-break matrix, and
 * charges — everything the standalone service endpoints return.
 *
 * <p>Both reads are cached with a TTL ({@link CatalogCacheProperties}) because the console re-asks
 * for the same data constantly — paging, re-filtering and plain page reloads all used to replay the
 * full set of SOAP calls.
 */
@Service
public class CatalogSummaryService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSummaryService.class);

    /** Keys for the two catalog-wide id lists (few entries, same TTL machinery). */
    private static final String LIST_KEY = "sellable-product-ids";
    private static final String CLOSE_OUT_KEY = "close-out-product-ids";

    private final ProductDataService productData;
    private final PricingService pricing;
    private final InventoryService inventory;
    private final MediaService media;
    private final PricingPolicy pricingPolicy;
    private final ShopifySyncService sync;
    private final ShopifyProperties shopify;
    private final SyncProperties props;

    private final TtlCache<String, ProductDetail> detailCache;
    private final TtlCache<String, List<String>> listCache;
    private final ExecutorService fetchPool;
    /** Absent unless persistence is enabled; absence means "serve the catalog from the supplier". */
    private final ObjectProvider<CatalogStore> stores;

    public CatalogSummaryService(ProductDataService productData, PricingService pricing,
                                 InventoryService inventory, MediaService media, PricingPolicy pricingPolicy,
                                 ShopifySyncService sync, ShopifyProperties shopify, SyncProperties props,
                                 CatalogCacheProperties cacheProps, ObjectProvider<CatalogStore> stores) {
        this.stores = stores;
        this.productData = productData;
        this.pricing = pricing;
        this.inventory = inventory;
        this.media = media;
        this.pricingPolicy = pricingPolicy;
        this.sync = sync;
        this.shopify = shopify;
        this.props = props;
        this.detailCache = new TtlCache<>(cacheProps.detailTtl(), cacheProps.maxProducts());
        this.listCache = new TtlCache<>(cacheProps.listTtl(), 4);   // sellable ids + close-out ids
        this.fetchPool = Executors.newFixedThreadPool(Math.max(1, cacheProps.fetchThreads()),
                r -> {
                    Thread t = new Thread(r, "catalog-fetch");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    void shutdown() {
        fetchPool.shutdownNow();
    }

    /**
     * @return one cheap entry per discoverable product (id + Shopify imported flag). This is the
     * catalog's single Shopify listing: it refreshes the supplier-id index that the per-row
     * {@link #detail(String)} calls then read for free.
     */
    public List<CatalogEntry> listProductIds() {
        Set<String> importedIds = importedIdsOrNull();
        List<CatalogRow> mirrored = fromMirror();
        if (mirrored != null) {
            List<CatalogEntry> entries = new ArrayList<>();
            for (CatalogRow row : mirrored) {
                entries.add(new CatalogEntry(row.productId(), imported(importedIds, row.productId()),
                        row.closeOut(), sync.hasDiscounts(row.productId())));
            }
            return entries;
        }
        Set<String> closeOutIds = closeOutIds();
        List<CatalogEntry> entries = new ArrayList<>();
        for (String productId : discoverProductIds()) {
            entries.add(new CatalogEntry(productId, imported(importedIds, productId),
                    closeOutIds.contains(productId.toUpperCase(Locale.ROOT)),
                    sync.hasDiscounts(productId)));
        }
        return entries;
    }

    /**
     * One page of the catalog, filtered and sorted by the database.
     *
     * @throws CatalogSearchUnavailableException when there is no mirror to query — searching the
     * whole catalog server-side is only possible against one, and silently returning a page of an
     * unfiltered list would be worse than saying so.
     */
    public CatalogQuery.Page search(CatalogQuery.Request request) {
        CatalogStore store = stores.getIfAvailable();
        if (store == null) {
            throw new CatalogSearchUnavailableException(
                    "Server-side catalog search needs the database (sync.persistence.enabled=true); "
                            + "without it the console filters the full list in the browser");
        }
        return store.search(request);
    }

    /**
     * @return the mirrored catalog, or null when there is no usable mirror — no database configured,
     * nothing scanned into it yet, or the database is unreachable. Null means "ask the supplier",
     * which is what this service did before the mirror existed. A database outage therefore degrades
     * the console rather than breaking it.
     */
    private List<CatalogRow> fromMirror() {
        CatalogStore store = stores.getIfAvailable();
        if (store == null) {
            return null;
        }
        try {
            List<CatalogRow> rows = store.findAll();
            return rows.isEmpty() ? null : rows;
        } catch (RuntimeException e) {
            log.warn("Catalog mirror unavailable, falling back to the supplier: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Supplier product ids flagged as close-out (discontinued / being sold off), UPPER-cased for
     * case-insensitive matching against the sellable list. One extra upstream call, cached on the
     * same window as the id list; a supplier that doesn't answer it just means no badges.
     */
    private Set<String> closeOutIds() {
        try {
            return new LinkedHashSet<>(listCache.get(CLOSE_OUT_KEY, key -> {
                Set<String> ids = new LinkedHashSet<>();
                for (ProductCloseOut c : productData.getProductCloseOut()) {
                    if (c.productId() != null) {
                        ids.add(c.productId().toUpperCase(Locale.ROOT));
                    }
                }
                return List.copyOf(ids);
            }));
        } catch (RuntimeException e) {
            log.debug("close-out list unavailable: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Full per-service detail for one product (lazy per row): inventory, pricing, charges.
     *
     * <p>The four services are read <b>in parallel</b> (they are independent, and in series this was
     * the row's whole latency) and <b>independently fault-tolerant</b>: a service that has nothing
     * for this id costs its section and a warning, not the whole row. That is what a listed-but-
     * unknown id like GI840 needs — PaceSetter's sellable catalog is wider than its Product Data, so
     * a missing product record is normal, and inventory/pricing/media often still have the goods.
     * Only when <em>every</em> service comes up empty is this a 404.
     *
     * <p>The assembled result is cached for {@link CatalogCacheProperties#detailTtl()}; the Shopify
     * {@code imported} flag is stamped fresh on top of the cached body.
     */
    public ProductDetail detail(String productId) {
        return detailCache.get(productId, this::loadDetail).withImported(sync.isImported(productId));
    }

    private ProductDetail loadDetail(String productId) {
        String country = props.country();
        String language = props.language();
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<Product> productFuture = section(warnings, "Product Data",
                () -> productData.getProduct(productId, country, language), null);
        CompletableFuture<List<String>> imagesFuture = section(warnings, "Media",
                () -> new ArrayList<>(new LinkedHashSet<>(
                        media.getMediaContent(productId, "Image", null).stream()
                                .map(MediaContent::url).filter(u -> u != null && !u.isBlank()).toList())),
                List.of());
        CompletableFuture<List<InventoryRow>> inventoryFuture = section(warnings, "Inventory",
                () -> inventory.getInventoryLevels(productId, null).parts().stream()
                        .map(p -> new InventoryRow(p.partId(), p.color(), p.size(),
                                p.partDescription() != null ? p.partDescription() : p.partBrand(),
                                p.quantityAvailable()))
                        .toList(),
                List.of());
        // One Pricing call yields two sections: the price-break matrix and the imprint locations.
        CompletableFuture<Configuration> configFuture = section(warnings, "Pricing",
                () -> pricing.getConfigurationAndPricingWithList(
                        productId, props.currency(), null, null, country, language),
                null);
        CompletableFuture<List<ChargeRow>> chargesFuture = section(warnings, "Charges",
                () -> pricing.getAvailableCharges(productId, country, language).stream()
                        .map(c -> new ChargeRow(c.chargeId(), c.chargeName(), c.chargeType(),
                                c.priceBreaks().isEmpty() ? null : c.priceBreaks().get(0).price()))
                        .toList(),
                List.of());

        Product product = productFuture.join();
        List<String> imageUrls = imagesFuture.join();
        List<InventoryRow> inventoryRows = inventoryFuture.join();
        Configuration config = configFuture.join();
        List<ChargeRow> charges = chargesFuture.join();

        List<PricePart> pricingParts = config == null ? List.of()
                : config.partPrices().stream().map(this::toPricePart).toList();
        List<DecorationLocation> decorationLocations = config == null ? List.of()
                : config.locations().stream().map(CatalogSummaryService::toDecorationLocation).toList();

        if (product == null && imageUrls.isEmpty() && inventoryRows.isEmpty() && pricingParts.isEmpty()) {
            throw new PromoStandardsNotFoundException(
                    "No supplier service returned data for productId=" + productId);
        }

        List<String> tags = product == null || product.categories() == null ? List.of() : product.categories();
        String productType = tags.isEmpty() ? null : tags.get(tags.size() - 1);
        String title = product == null ? productId : product.productName();

        // Imported flag is stamped by the caller from the cached supplier-id index — NOT a fresh
        // listing. Paging the whole store here made a 25-row table page cost 25 full Shopify
        // paginations for a field the catalog list already carries.
        return new ProductDetail(productId, title,
                product == null ? null : product.description(),
                product == null ? null : CatalogService.norm(product.productBrand()),
                productType, tags, imageUrls, null, inventoryRows, pricingParts, charges,
                product == null, List.copyOf(warnings), decorationLocations);
    }

    private static DecorationLocation toDecorationLocation(Configuration.Location location) {
        List<DecorationArea> areas = location.decorations().stream()
                .map(d -> new DecorationArea(d.decorationId(), d.decorationName(), d.geometry(),
                        d.height(), d.width(), d.diameter(), d.uom(), d.defaultDecoration()))
                .toList();
        return new DecorationLocation(location.locationId(), location.locationName(),
                location.defaultLocation(), location.decorationsIncluded(),
                location.minDecoration(), location.maxDecoration(), areas);
    }

    /**
     * Runs one service read on the fetch pool, degrading to {@code fallback} (plus a warning) when
     * that service fails or has no record. Genuine outages are therefore visible in the payload
     * rather than silently indistinguishable from an empty catalog entry.
     */
    private <T> CompletableFuture<T> section(List<String> warnings, String name,
                                             Supplier<T> read, T fallback) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return read.get();
            } catch (PromoStandardsNotFoundException e) {
                warnings.add(name + ": no record for this product");
                return fallback;
            } catch (RuntimeException e) {
                log.debug("{} unavailable: {}", name, e.getMessage());
                warnings.add(name + ": " + e.getMessage());
                return fallback;
            }
        }, fetchPool);
    }

    private PricePart toPricePart(Configuration.PartPrice pp) {
        // The first break is the base: its published price is what the Shopify variant costs, and
        // every later break is expressed as an amount off it — the shape the console shows and the
        // one the shopper is shown ("order 6+ and pay $178.99 each").
        BigDecimal retail = pp.priceBreaks().stream()
                .min(Comparator.comparingInt(Configuration.PriceBreak::minQuantity))
                .map(b -> pricingPolicy.retailPrice(b.price(), b.listPrice()))
                .orElse(null);
        List<PriceBreak> breaks = pp.priceBreaks().stream()
                .map(b -> {
                    BigDecimal tier = pricingPolicy.retailPrice(b.price(), b.listPrice());
                    BigDecimal discount = retail == null || tier == null || retail.compareTo(tier) <= 0
                            ? null : retail.subtract(tier);
                    return new PriceBreak(b.minQuantity(), b.price(), b.listPrice(), tier, discount,
                            b.priceUom());
                })
                .toList();
        return new PricePart(pp.partId(), pp.description(), retail, breaks);
    }

    /**
     * Distinct sellable product ids — a single upstream call (the catalog's id source), cached for
     * {@link CatalogCacheProperties#listTtl()}: the sellable catalog changes daily at most, while
     * every console load, tab and user asks for it.
     */
    private List<String> discoverProductIds() {
        return listCache.get(LIST_KEY, key -> {
            Set<String> ids = new LinkedHashSet<>();
            for (ProductSellable s : productData.getProductSellable(null, true)) {
                if (s.productId() != null) {
                    ids.add(s.productId());
                }
            }
            return List.copyOf(ids);
        });
    }

    /** Case-insensitive: migration ids parsed from the legacy DB may differ in case from the feed. */
    private Boolean imported(Set<String> importedIds, String productId) {
        return importedIds == null ? null : importedIds.contains(productId.toUpperCase(Locale.ROOT));
    }

    /** @return the set of imported supplier ids, or null when Shopify isn't connected/reachable. */
    private Set<String> importedIdsOrNull() {
        if (shopify.storeDomain() == null || shopify.storeDomain().isBlank()) {
            return null;
        }
        try {
            Set<String> upper = new LinkedHashSet<>();
            for (String id : sync.listImportedProductIds()) {
                upper.add(id.toUpperCase(Locale.ROOT));
            }
            return upper;
        } catch (RuntimeException e) {
            log.warn("Could not list imported products from Shopify: {}", e.getMessage());
            return null;
        }
    }
}
