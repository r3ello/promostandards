package com.trophy.promostandards.sync;

import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One throttled {@code getProduct} pass over the whole sellable catalog, shared by every feature
 * that needs catalog-wide Product Data.
 *
 * <p>This exists to stop that pass from being run twice. Both catalog-wide indexes — the "group
 * variants" families ({@link CatalogGroupIndex}) and the search titles ({@link CatalogTitleIndex}) —
 * need exactly the same thing: every product's record. They are also both kicked off by the same
 * console load, so without sharing, opening the console would fire two full passes at the supplier
 * back to back. The result is memoised briefly ({@link #SCAN_TTL}) with single-flight semantics, so
 * the second index to ask either joins the running pass or reuses its result.
 *
 * <p>Per-product failures are tolerated: an id whose record cannot be read is simply absent from
 * {@link Scan#products()} while remaining in {@link Scan#productIds()} — the supplier lists ids its
 * Product Data service has no record of, and neither index should fall over on those.
 */
@Service
public class SupplierProductScan {

    private static final Logger log = LoggerFactory.getLogger(SupplierProductScan.class);

    /** Long enough for two indexes triggered by one console load to share a pass, short enough to
     * never be mistaken for the indexes' own (hours-long, persisted) caching. */
    private static final Duration SCAN_TTL = Duration.ofMinutes(10);
    private static final int FETCH_CONCURRENCY = 5;
    private static final String SCAN_KEY = "catalog-scan";

    private final ProductDataService productData;
    private final SyncProperties props;
    private final TtlCache<String, Scan> cache = new TtlCache<>(SCAN_TTL, 1);

    public SupplierProductScan(ProductDataService productData, SyncProperties props) {
        this.productData = productData;
        this.props = props;
    }

    /**
     * @param productIds every distinct sellable product id, in catalog order
     * @param products   product records by id — only the ids Product Data actually has a record for
     */
    public record Scan(List<String> productIds, Map<String, Product> products) {
    }

    /** @return the catalog scan, running it only if no recent one exists (single-flight). */
    public Scan scan() {
        return cache.get(SCAN_KEY, key -> runScan());
    }

    private Scan runScan() {
        List<String> ids = discoverProductIds();
        if (ids.isEmpty()) {
            return new Scan(List.of(), Map.of());
        }
        Map<String, Product> products = new ConcurrentHashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_CONCURRENCY, ids.size()));
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String id : ids) {
                futures.add(CompletableFuture.runAsync(() -> {
                    Product product = fetch(id);
                    if (product != null) {
                        products.put(id, product);
                    }
                }, pool));
            }
            futures.forEach(CompletableFuture::join);
        } finally {
            pool.shutdown();
        }
        log.info("Catalog scan: read {} product records of {} sellable ids", products.size(), ids.size());
        // Preserve catalog order for stable, diff-friendly downstream output.
        Map<String, Product> ordered = new LinkedHashMap<>();
        for (String id : ids) {
            Product product = products.get(id);
            if (product != null) {
                ordered.put(id, product);
            }
        }
        return new Scan(List.copyOf(ids), Map.copyOf(ordered));
    }

    private Product fetch(String id) {
        try {
            return productData.getProduct(id, props.country(), props.language());
        } catch (RuntimeException e) {
            log.debug("catalog scan: getProduct({}) failed, skipping: {}", id, e.getMessage());
            return null;
        }
    }

    private List<String> discoverProductIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ProductSellable s : productData.getProductSellable(null, true)) {
            if (s.productId() != null) {
                ids.add(s.productId());
            }
        }
        return new ArrayList<>(ids);
    }
}
