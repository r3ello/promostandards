package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.service.InventoryService;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.sync.model.PendingDataView;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The catalog ids whose Inventory service answers nothing — products that are not ready to import.
 *
 * <p>PaceSetter sells ids its own Inventory service answers "ProductID not found" for. Imported, such
 * a product has no stock to publish (the console shows it as "No data"); listed under "Not imported"
 * it reads as something waiting for a click when it is really waiting for the supplier. The console
 * needs to know which ones they are <em>before</em> a row's detail is loaded — the table only ever
 * loads the visible page — so this answers it for the whole catalog at once: one throttled
 * {@code getInventoryLevels} per sellable id, cached and persisted, rebuilt in the background.
 *
 * <p>Same shape as {@link CatalogTitleIndex}: built on demand (never at startup), the first view may
 * say {@code building}, and a failed call for one id marks that id pending rather than failing the
 * build — which is exactly what the supplier's own "not found" looks like from here.
 */
@Service
public class PendingDataIndex {

    private static final Logger log = LoggerFactory.getLogger(PendingDataIndex.class);
    /** The same ceiling the catalog scan uses on concurrent supplier calls. */
    private static final int FETCH_CONCURRENCY = 5;

    private final ProductDataService productData;
    private final InventoryService inventory;
    private final PendingDataProperties props;
    private final ObjectMapper objectMapper;
    private final SyncProperties sync;
    private final PromoStandardsProperties promoStandards;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final AtomicBoolean building = new AtomicBoolean(false);

    public PendingDataIndex(ProductDataService productData, InventoryService inventory,
                            PendingDataProperties props, ObjectMapper objectMapper, SyncProperties sync,
                            PromoStandardsProperties promoStandards) {
        this.productData = productData;
        this.inventory = inventory;
        this.props = props;
        this.objectMapper = objectMapper;
        this.sync = sync;
        this.promoStandards = promoStandards;
    }

    /** Immutable point-in-time index; {@code builtAt == null} means "never built". */
    record Snapshot(Instant builtAt, List<String> productIds) {
        static final Snapshot EMPTY = new Snapshot(null, List.of());

        boolean isEmpty() {
            return builtAt == null;
        }
    }

    /** Persisted cache shape; {@code source} keeps a stub-built file from being served live. */
    record Persisted(String source, Instant builtAt, List<String> productIds) {
    }

    private String currentSource() {
        return sync.supplierCode() + "/" + promoStandards.getInventory().getMode();
    }

    @PostConstruct
    void load() {
        if (props.cacheFile() == null || props.cacheFile().isBlank()) {
            return;
        }
        Path file = Path.of(props.cacheFile());
        if (!Files.exists(file)) {
            return;
        }
        try {
            Persisted p = objectMapper.readValue(file.toFile(), Persisted.class);
            if (!currentSource().equals(p.source())) {
                log.info("Ignoring pending-data cache {}: built from '{}', now running '{}'",
                        file, p.source(), currentSource());
                return;
            }
            snapshot = new Snapshot(p.builtAt(), p.productIds() == null ? List.of() : p.productIds());
            log.info("Loaded pending-data index from {} ({} ids)", file, snapshot.productIds().size());
        } catch (IOException e) {
            log.warn("Could not read pending-data cache {}: {}", file, e.getMessage());
        }
    }

    /** Current view; triggers a background build when empty or stale. */
    public PendingDataView view() {
        if (props.enabled() && (snapshot.isEmpty() || isStale(snapshot))) {
            triggerBuild();
        }
        return toView();
    }

    /** Force a rebuild in the background (no-op if one is running); returns the current view. */
    public PendingDataView refresh() {
        if (props.enabled()) {
            triggerBuild();
        }
        return toView();
    }

    private PendingDataView toView() {
        Snapshot snap = snapshot;
        String status = !snap.isEmpty() ? "ready" : (building.get() ? "building" : "empty");
        return new PendingDataView(status, snap.builtAt(), snap.productIds().size(), snap.productIds());
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
            log.warn("Pending-data index build failed: {}", e.getMessage());
        } finally {
            building.set(false);
        }
    }

    /** Build synchronously (used by the async trigger and by tests). */
    Snapshot buildNow() {
        Set<String> ids = new LinkedHashSet<>();
        for (ProductSellable s : productData.getProductSellable(null, true)) {
            if (s.productId() != null) {
                ids.add(s.productId());
            }
        }
        Set<String> pending = ConcurrentHashMap.newKeySet();
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(FETCH_CONCURRENCY, ids.size())));
        try {
            List<CompletableFuture<Void>> calls = new ArrayList<>();
            for (String id : ids) {
                calls.add(CompletableFuture.runAsync(() -> {
                    if (!hasInventory(id)) {
                        pending.add(id);
                    }
                }, pool));
            }
            calls.forEach(CompletableFuture::join);
        } finally {
            pool.shutdown();
        }
        // Catalog order, so the file diffs cleanly between builds.
        List<String> ordered = ids.stream().filter(pending::contains).toList();
        log.info("Built pending-data index: {} of {} sellable ids have no inventory data",
                ordered.size(), ids.size());
        return new Snapshot(Instant.now(), ordered);
    }

    /** A "not found" and an empty answer mean the same thing here: nothing to publish as stock. */
    private boolean hasInventory(String id) {
        try {
            InventoryLevels levels = inventory.getInventoryLevels(id, null);
            return levels != null && levels.parts() != null && !levels.parts().isEmpty();
        } catch (RuntimeException e) {
            log.debug("pending-data: inventory for {} unavailable: {}", id, e.getMessage());
            return false;
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

    private void persist(Snapshot snap) {
        if (props.cacheFile() == null || props.cacheFile().isBlank()) {
            return;
        }
        Path file = Path.of(props.cacheFile());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            objectMapper.writeValue(file.toFile(),
                    new Persisted(currentSource(), snap.builtAt(), snap.productIds()));
        } catch (IOException e) {
            log.warn("Could not write pending-data cache {}: {}", file, e.getMessage());
        }
    }
}
