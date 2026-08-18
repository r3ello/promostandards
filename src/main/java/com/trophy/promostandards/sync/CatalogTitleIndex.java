package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.db.CatalogRow;
import com.trophy.promostandards.db.CatalogStore;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.sync.model.CatalogTitleView;
import com.trophy.promostandards.sync.model.CatalogTitleView.CatalogTitle;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds and caches the catalog title index: product name + vendor for every catalog id.
 *
 * <p>Without it the console's search box can only match product <b>ids</b> and the names of rows the
 * user happens to have scrolled past — searching "polo" found nothing, and every keystroke queued
 * more per-row fetches trying. With it, the front end holds every name up front and filters locally:
 * search becomes instant and costs the supplier nothing.
 *
 * <p>Same shape as {@link CatalogGroupIndex} (on-demand background build, in-memory + on-disk cache,
 * TTL) and, crucially, the same {@link SupplierProductScan} — so enabling both indexes costs one
 * catalog pass, not two.
 */
@Service
public class CatalogTitleIndex {

    private static final Logger log = LoggerFactory.getLogger(CatalogTitleIndex.class);

    private final SupplierProductScan scan;
    private final CatalogTitleProperties props;
    private final ObjectMapper objectMapper;
    private final ProductDataService productData;
    private final ObjectProvider<CatalogStore> stores;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final AtomicBoolean building = new AtomicBoolean(false);

    public CatalogTitleIndex(SupplierProductScan scan, CatalogTitleProperties props,
                             ObjectMapper objectMapper, ProductDataService productData,
                             ObjectProvider<CatalogStore> stores) {
        this.scan = scan;
        this.props = props;
        this.objectMapper = objectMapper;
        this.productData = productData;
        this.stores = stores;
    }

    /** Immutable point-in-time index; {@code builtAt == null} means "never built". */
    record Snapshot(Instant builtAt, List<CatalogTitle> titles) {
        static final Snapshot EMPTY = new Snapshot(null, List.of());

        boolean isEmpty() {
            return builtAt == null;
        }
    }

    /** Persisted cache shape (status/counts are derived, not stored). */
    record Persisted(Instant builtAt, List<CatalogTitle> titles) {
    }

    /**
     * Warms the in-memory index at startup from whichever backing store is active: the catalog
     * mirror when persistence is on, the JSON cache otherwise. Failures here are never fatal — the
     * index simply rebuilds on first use.
     */
    @PostConstruct
    void load() {
        CatalogStore store = stores.getIfAvailable();
        if (store == null) {
            loadFromDisk();
            return;
        }
        try {
            List<CatalogRow> rows = store.findAll();
            Instant scannedAt = store.lastScanAt().orElse(null);
            if (rows.isEmpty() || scannedAt == null) {
                return;     // mirror not populated yet; the first view() triggers a build
            }
            snapshot = new Snapshot(scannedAt, rows.stream()
                    .filter(row -> row.title() != null)
                    .map(row -> new CatalogTitle(row.productId(), row.title(), row.vendor()))
                    .toList());
            log.info("Loaded catalog title index from the mirror ({} titles)", snapshot.titles().size());
        } catch (RuntimeException e) {
            log.warn("Could not read the catalog mirror: {}", e.getMessage());
        }
    }

    void loadFromDisk() {
        if (props.cacheFile() == null || props.cacheFile().isBlank()) {
            return;
        }
        Path file = Path.of(props.cacheFile());
        if (!Files.exists(file)) {
            return;
        }
        try {
            Persisted p = objectMapper.readValue(file.toFile(), Persisted.class);
            snapshot = new Snapshot(p.builtAt(), p.titles() == null ? List.of() : p.titles());
            log.info("Loaded catalog title index from {} ({} titles)", file, snapshot.titles().size());
        } catch (IOException e) {
            log.warn("Could not read catalog title index cache {}: {}", file, e.getMessage());
        }
    }

    /**
     * Current index view. When enabled and the index is empty or stale, a background build is
     * triggered; existing data (if any) is returned immediately meanwhile.
     */
    public CatalogTitleView view() {
        if (props.enabled() && (snapshot.isEmpty() || isStale(snapshot))) {
            triggerBuild();
        }
        return toView();
    }

    /** Force a rebuild in the background (no-op if one is already running); returns the current view. */
    public CatalogTitleView refresh() {
        triggerBuild();
        return toView();
    }

    private CatalogTitleView toView() {
        Snapshot snap = snapshot;
        String status = !snap.isEmpty() ? "ready" : (building.get() ? "building" : "empty");
        return new CatalogTitleView(status, snap.builtAt(), snap.titles().size(), snap.titles());
    }

    private void triggerBuild() {
        if (building.compareAndSet(false, true)) {
            CompletableFuture.runAsync(this::runBuild);
        }
    }

    private void runBuild() {
        try {
            snapshot = buildNow();
            persist(snapshot);
        } catch (RuntimeException e) {
            log.warn("Catalog title index build failed: {}", e.getMessage());
        } finally {
            building.set(false);
        }
    }

    /** Build synchronously (used by the async trigger and by tests). */
    Snapshot buildNow() {
        SupplierProductScan.Scan catalog = scan.scan();
        List<CatalogTitle> titles = new ArrayList<>();
        for (Map.Entry<String, Product> entry : catalog.products().entrySet()) {
            Product product = entry.getValue();
            titles.add(new CatalogTitle(entry.getKey(), product.productName(),
                    CatalogService.norm(product.productBrand())));
        }
        log.info("Built catalog title index: {} titles", titles.size());
        saveMirror(catalog);
        return new Snapshot(Instant.now(), List.copyOf(titles));
    }

    /**
     * Writes the catalog mirror when persistence is on. This build already holds the one expensive
     * thing the mirror needs — a product record per id — so the mirror rides along rather than
     * paying for a second catalog pass.
     *
     * <p>Unlike the title snapshot, the mirror keeps ids the Product Data service has no record for
     * (flagged {@code productDataMissing}): they are still sellable, still orderable, and still shown
     * in the console.
     */
    private void saveMirror(SupplierProductScan.Scan catalog) {
        CatalogStore store = stores.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            Set<String> closeOut = closeOutKeys();
            List<CatalogRow> rows = new ArrayList<>();
            for (String productId : catalog.productIds()) {
                Product product = catalog.products().get(productId);
                List<String> categories = product == null || product.categories() == null
                        ? List.of() : product.categories();
                rows.add(new CatalogRow(productId,
                        product == null ? null : product.productName(),
                        product == null ? null : CatalogService.norm(product.productBrand()),
                        categories.isEmpty() ? null : categories.get(categories.size() - 1),
                        closeOut.contains(productId.toUpperCase(Locale.ROOT)),
                        true,
                        product == null));
            }
            log.info("Catalog mirror: wrote {} products", store.saveCatalog(rows));
        } catch (RuntimeException e) {
            // The in-memory index is still good; only the mirror is stale. Never fail the build.
            log.warn("Could not write the catalog mirror: {}", e.getMessage());
        }
    }

    /** Close-out ids, upper-cased. Unavailable simply means no product is flagged. */
    private Set<String> closeOutKeys() {
        try {
            Set<String> keys = new LinkedHashSet<>();
            for (ProductCloseOut closeOut : productData.getProductCloseOut()) {
                if (closeOut.productId() != null) {
                    keys.add(closeOut.productId().toUpperCase(Locale.ROOT));
                }
            }
            return keys;
        } catch (RuntimeException e) {
            log.debug("close-out list unavailable for the mirror: {}", e.getMessage());
            return Set.of();
        }
    }

    private boolean isStale(Snapshot snap) {
        if (snap.builtAt() == null) {
            return true;
        }
        Duration ttl = props.ttl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return snap.builtAt().plus(ttl).isBefore(Instant.now());
    }

    /** JSON cache is the no-database fallback; with the mirror on, the database is the cache. */
    private void persist(Snapshot snap) {
        if (stores.getIfAvailable() != null) {
            return;
        }
        if (props.cacheFile() == null || props.cacheFile().isBlank()) {
            return;
        }
        Path file = Path.of(props.cacheFile());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            objectMapper.writeValue(file.toFile(), new Persisted(snap.builtAt(), snap.titles()));
        } catch (IOException e) {
            log.warn("Could not write catalog title index cache {}: {}", file, e.getMessage());
        }
    }
}
