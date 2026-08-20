package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.db.CatalogStore;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.Product.RelatedProduct;
import com.trophy.promostandards.sync.model.CatalogGroupView;
import com.trophy.promostandards.sync.model.ProductGroup;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Builds and caches the catalog "group variants" index: sibling supplier product ids (one product
 * the supplier split across several ids, e.g. one per size) grouped into families from two sources,
 * unioned together:
 * <ol>
 *   <li>the supplier's {@code Common Grouping} related products (PaceSetter publishes none — this
 *       source yields 0 families there, but stays for suppliers that do), and</li>
 *   <li>the {@code custom.ps_product_ids} lists on PromoStandards-tagged store products — the
 *       families the trophypartner migration carried over from the legacy DB (the authoritative
 *       grouping for PaceSetter).</li>
 * </ol>
 *
 * <p>Building it needs the expensive pass — one {@code getProduct} per product — which it no longer
 * runs itself: it asks {@link SupplierProductScan}, so it shares one throttled pass with the title
 * index instead of each firing its own. Beyond that it is:
 * <ul>
 *   <li><b>on demand only</b>: never built at startup; the first call to {@link #view()} (or an
 *       explicit {@link #refresh()}) kicks off an async build. Startup only <i>loads</i> the cache.</li>
 *   <li><b>cached</b>: kept in memory and persisted to {@code sync.group-index.cache-file} so a
 *       restart reuses the last index instead of repeating the heavy pass.</li>
 * </ul>
 * The result carries only multi-member families; any product id not present is standalone.
 */
@Service
public class CatalogGroupIndex {

    private static final Logger log = LoggerFactory.getLogger(CatalogGroupIndex.class);
    private static final String COMMON_GROUPING = "Common Grouping";

    private final SupplierProductScan scan;
    private final CatalogGroupProperties props;
    private final ObjectMapper objectMapper;
    private final ShopifySyncService shopifySync;
    private final ObjectProvider<CatalogStore> stores;
    private final SyncProperties sync;
    private final PromoStandardsProperties promoStandards;

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final AtomicBoolean building = new AtomicBoolean(false);

    public CatalogGroupIndex(SupplierProductScan scan, CatalogGroupProperties props,
                             ObjectMapper objectMapper, ShopifySyncService shopifySync,
                             ObjectProvider<CatalogStore> stores, SyncProperties sync,
                             PromoStandardsProperties promoStandards) {
        this.scan = scan;
        this.props = props;
        this.objectMapper = objectMapper;
        this.shopifySync = shopifySync;
        this.stores = stores;
        this.sync = sync;
        this.promoStandards = promoStandards;
    }

    /** Immutable point-in-time index; {@code builtAt == null} means "never built". */
    record Snapshot(Instant builtAt, List<ProductGroup> groups) {
        static final Snapshot EMPTY = new Snapshot(null, List.of());

        boolean isEmpty() {
            return builtAt == null;
        }
    }

    /**
     * Persisted cache shape (status/counts are derived, not stored).
     *
     * @param source which data this was built from — see {@link #currentSource()}. A cache whose
     *               source no longer matches is discarded: a file written against the in-memory
     *               stubs would otherwise be served to a live console, since the TTL alone considers
     *               it fresh.
     */
    record Persisted(String source, Instant builtAt, List<ProductGroup> groups) {
    }

    /** Supplier + client mode, so a stub-built cache is never served to a live run. */
    private String currentSource() {
        return sync.supplierCode() + "/" + promoStandards.getProductData().getMode();
    }

    /** Warms from the mirror when persistence is on, from the JSON cache otherwise. */
    @PostConstruct
    void load() {
        CatalogStore store = stores.getIfAvailable();
        if (store == null) {
            loadFromDisk();
            return;
        }
        try {
            List<ProductGroup> groups = store.findGroups();
            if (!groups.isEmpty()) {
                snapshot = new Snapshot(store.lastScanAt().orElse(Instant.now()), groups);
                log.info("Loaded catalog group index from the mirror ({} families)", groups.size());
            }
        } catch (RuntimeException e) {
            log.warn("Could not read stored product groups: {}", e.getMessage());
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
            if (!currentSource().equals(p.source())) {
                log.info("Ignoring catalog group index cache {}: built from '{}', now running '{}'",
                        file, p.source(), currentSource());
                return;
            }
            snapshot = new Snapshot(p.builtAt(), p.groups() == null ? List.of() : p.groups());
            log.info("Loaded catalog group index from {} ({} families)", file, snapshot.groups().size());
        } catch (IOException e) {
            log.warn("Could not read catalog group index cache {}: {}", file, e.getMessage());
        }
    }

    /**
     * Current index view. When grouping is enabled and the index is empty or stale, a background
     * build is triggered; existing data (if any) is returned immediately meanwhile.
     */
    public CatalogGroupView view() {
        if (props.enabled() && (snapshot.isEmpty() || isStale(snapshot))) {
            triggerBuild();
        }
        return toView();
    }

    /** Force a rebuild in the background (no-op if one is already running); returns the current view. */
    public CatalogGroupView refresh() {
        triggerBuild();
        return toView();
    }

    private CatalogGroupView toView() {
        Snapshot snap = snapshot;
        String status = !snap.isEmpty() ? "ready" : (building.get() ? "building" : "empty");
        int productCount = snap.groups().stream().mapToInt(g -> g.memberIds().size()).sum();
        return new CatalogGroupView(status, snap.builtAt(), productCount, snap.groups().size(), snap.groups());
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
            log.warn("Catalog group index build failed: {}", e.getMessage());
        } finally {
            building.set(false);
        }
    }

    /**
     * Build the index synchronously (used by the async trigger and by tests). Discovers all sellable
     * product ids, collects sibling edges from Common Grouping links (throttled) and from the store's
     * {@code ps_product_ids} lists, and unions them into families of size &gt;= 2.
     */
    Snapshot buildNow() {
        SupplierProductScan.Scan catalog = scan.scan();
        List<String> ids = catalog.productIds();
        Set<String> idSet = new LinkedHashSet<>(ids);
        Map<String, List<String>> edges = groupingEdges(catalog, idSet);
        mergeInto(edges, migrationEdges(idSet));
        List<ProductGroup> groups = unionFind(ids, edges);
        log.info("Built catalog group index: {} families across {} products (of {} total)",
                groups.size(), groups.stream().mapToInt(g -> g.memberIds().size()).sum(), ids.size());
        return new Snapshot(Instant.now(), groups);
    }

    /**
     * Sibling edges from the migrated store products' {@code ps_product_ids} lists. Metafield values
     * are matched to catalog ids case-insensitively (they were parsed from the legacy DB); ids not in
     * the sellable catalog are ignored. Empty when Shopify is unconfigured or unreachable.
     */
    private Map<String, List<String>> migrationEdges(Set<String> idSet) {
        Map<String, String> byUpper = new HashMap<>();
        for (String id : idSet) {
            byUpper.putIfAbsent(id.toUpperCase(Locale.ROOT), id);
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        int families = 0;
        for (ShopifySyncService.ImportedProduct p : shopifySync.importedProductsOrEmpty()) {
            List<String> members = new ArrayList<>();
            for (String supplierId : p.supplierIds()) {
                String catalogId = byUpper.get(supplierId.toUpperCase(Locale.ROOT));
                if (catalogId != null && !members.contains(catalogId)) {
                    members.add(catalogId);
                }
            }
            if (members.size() < 2) {
                continue;
            }
            families++;
            edges.computeIfAbsent(members.get(0), k -> new ArrayList<>())
                    .addAll(members.subList(1, members.size()));
        }
        if (families > 0) {
            log.info("Catalog group index: {} families contributed by store ps_product_ids", families);
        }
        return edges;
    }

    private static void mergeInto(Map<String, List<String>> target, Map<String, List<String>> extra) {
        extra.forEach((id, siblings) ->
                target.computeIfAbsent(id, k -> new ArrayList<>()).addAll(siblings));
    }

    /** Common Grouping links per product id, keeping only edges to ids that are themselves in the catalog. */
    private Map<String, List<String>> groupingEdges(SupplierProductScan.Scan catalog, Set<String> idSet) {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Map.Entry<String, Product> entry : catalog.products().entrySet()) {
            String id = entry.getKey();
            List<String> siblings = new ArrayList<>();
            for (RelatedProduct related : entry.getValue().relatedProducts()) {
                if (COMMON_GROUPING.equalsIgnoreCase(related.relationType())
                        && related.productId() != null
                        && !related.productId().equals(id)
                        && idSet.contains(related.productId())) {
                    siblings.add(related.productId());
                }
            }
            if (!siblings.isEmpty()) {
                edges.put(id, siblings);
            }
        }
        return edges;
    }

    private static List<ProductGroup> unionFind(List<String> ids, Map<String, List<String>> edges) {
        Map<String, String> parent = new HashMap<>();
        for (String id : ids) {
            parent.put(id, id);
        }
        for (Map.Entry<String, List<String>> e : edges.entrySet()) {
            for (String other : e.getValue()) {
                union(parent, e.getKey(), other);
            }
        }
        Map<String, List<String>> components = new LinkedHashMap<>();
        for (String id : ids) {
            components.computeIfAbsent(find(parent, id), k -> new ArrayList<>()).add(id);
        }
        List<ProductGroup> groups = new ArrayList<>();
        for (List<String> members : components.values()) {
            if (members.size() >= 2) {
                List<String> sorted = new ArrayList<>(members);
                Collections.sort(sorted);
                groups.add(new ProductGroup(sorted.get(0), sorted));
            }
        }
        groups.sort(Comparator.comparing(ProductGroup::primaryId));
        return groups;
    }

    private static String find(Map<String, String> parent, String id) {
        String root = id;
        while (!root.equals(parent.get(root))) {
            root = parent.get(root);
        }
        // path compression
        String cur = id;
        while (!cur.equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        if (!parent.containsKey(a) || !parent.containsKey(b)) {
            return;
        }
        String ra = find(parent, a);
        String rb = find(parent, b);
        if (!ra.equals(rb)) {
            parent.put(ra, rb);
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

    /** Families go to the mirror when persistence is on; the JSON cache is the fallback. */
    private void persist(Snapshot snap) {
        CatalogStore store = stores.getIfAvailable();
        if (store != null) {
            try {
                store.saveGroups(snap.groups());
            } catch (RuntimeException e) {
                log.warn("Could not store product groups: {}", e.getMessage());
            }
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
            objectMapper.writeValue(file.toFile(),
                    new Persisted(currentSource(), snap.builtAt(), snap.groups()));
        } catch (IOException e) {
            log.warn("Could not write catalog group index cache {}: {}", file, e.getMessage());
        }
    }
}
