package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.service.PricingService;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.CatalogStatusClassifier.StoreProduct;
import com.trophy.promostandards.sync.model.CatalogStatusView;
import com.trophy.promostandards.sync.model.CatalogStatusView.Category;
import com.trophy.promostandards.sync.model.CatalogStatusView.Entry;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The console's Status view: every PromoStandards product of the store sorted by where it stands
 * against PaceSetter, plus what PaceSetter sells that the store does not carry. It is
 * {@code tools/simulate_store_sync.py} moved into the app, so the answer to "how many are synced, and
 * why not the rest" no longer needs someone to run a script and read CSVs.
 *
 * <p>Cost, per build: the shared {@link SupplierProductScan} (product data, already run by the
 * console), the Inventory answers {@link PendingDataIndex} already keeps, one price read per sellable
 * id (the only calls this adds, at the scan's own concurrency of 5), and ~12 pages of the store. So it
 * is built in the background, at most once per {@link CatalogStatusProperties#ttl()}, and kept on disk
 * — same shape as the other catalog indexes: the first view may say {@code building}.
 */
@Service
public class CatalogStatusService {

    private static final Logger log = LoggerFactory.getLogger(CatalogStatusService.class);
    private static final int FETCH_CONCURRENCY = 5;

    private final SupplierProductScan scan;
    private final PricingService pricing;
    private final PendingDataIndex pendingData;
    private final ShopifyGraphQLClient gql;
    private final SyncProperties sync;
    private final CatalogStatusProperties props;
    private final ObjectMapper objectMapper;
    private final PromoStandardsProperties promoStandards;
    private final ShopifyProperties shopify;

    private volatile CatalogStatusView report;
    private final AtomicBoolean building = new AtomicBoolean(false);

    public CatalogStatusService(SupplierProductScan scan, PricingService pricing, PendingDataIndex pendingData,
                                ShopifyGraphQLClient gql, SyncProperties sync, CatalogStatusProperties props,
                                ObjectMapper objectMapper, PromoStandardsProperties promoStandards,
                                ShopifyProperties shopify) {
        this.scan = scan;
        this.pricing = pricing;
        this.pendingData = pendingData;
        this.gql = gql;
        this.sync = sync;
        this.props = props;
        this.objectMapper = objectMapper;
        this.promoStandards = promoStandards;
        this.shopify = shopify;
    }

    /** {@code trophy-partner.myshopify.com} -> {@code https://admin.shopify.com/store/trophy-partner/products/}. */
    String adminProducts() {
        String domain = shopify == null ? null : shopify.storeDomain();
        if (domain == null || domain.isBlank()) {
            return null;
        }
        String store = domain.trim().replaceFirst("^https?://", "").replaceFirst("\\.myshopify\\.com/?$", "");
        return "https://admin.shopify.com/store/" + store + "/products/";
    }

    /** Persisted shape; {@code source} keeps a stub-built report from being served live. */
    record Persisted(String source, CatalogStatusView report) {
    }

    private String currentSource() {
        return sync.supplierCode() + "/" + promoStandards.getProductData().getMode();
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
            if (!currentSource().equals(p.source()) || p.report() == null) {
                log.info("Ignoring catalog status cache {}: built from '{}', now running '{}'",
                        file, p.source(), currentSource());
                return;
            }
            report = p.report();
            log.info("Loaded catalog status from {} ({} store products)", file, report.products().size());
        } catch (IOException e) {
            log.warn("Could not read catalog status cache {}: {}", file, e.getMessage());
        }
    }

    /** The current report; starts a background build when there is none or it is stale. */
    public CatalogStatusView view() {
        CatalogStatusView current = report;
        if (props.enabled() && (current == null || isStale(current))) {
            triggerBuild();
        }
        return withState(report);
    }

    /** Starts a rebuild in the background (no-op if one is running); returns the current report. */
    public CatalogStatusView refresh() {
        if (props.enabled()) {
            triggerBuild();
        }
        return withState(report);
    }

    private CatalogStatusView withState(CatalogStatusView current) {
        boolean running = building.get();
        if (current == null) {
            return new CatalogStatusView(running ? "building" : "empty", running, null, Map.of(), List.of(),
                    List.of(), List.of(), 0, List.of(), adminProducts());
        }
        return new CatalogStatusView("ready", running, current.builtAt(), current.counts(), current.products(),
                current.newAtSupplier(), current.related(), current.notImportable(), current.warnings(),
                adminProducts());
    }

    private void triggerBuild() {
        if (building.compareAndSet(false, true)) {
            CompletableFuture.runAsync(() -> {
                try {
                    report = buildNow();
                    persist(report);
                } catch (RuntimeException e) {
                    log.warn("Catalog status build failed: {}", e.getMessage());
                } finally {
                    building.set(false);
                }
            });
        }
    }

    /** Builds synchronously: the async trigger, and tests. */
    CatalogStatusView buildNow() {
        Instant started = Instant.now();
        List<String> warnings = new ArrayList<>();
        List<StoreProduct> store = storeProducts();

        SupplierProductScan.Scan catalog = scan.scan();
        Map<String, List<String>> productParts = new HashMap<>();
        Map<String, String> names = new HashMap<>();
        catalog.products().forEach((id, product) -> {
            List<String> parts = partIds(product);
            if (!parts.isEmpty()) {
                productParts.put(id, parts);
            }
            if (product.productName() != null) {
                names.put(id, product.productName().trim());
            }
        });
        Map<String, List<String>> pricedParts = pricedParts(catalog.productIds());
        Map<String, List<String>> inventoryParts;
        try {
            inventoryParts = pendingData.inventoryParts();
        } catch (RuntimeException e) {
            inventoryParts = Map.of();
        }
        if (inventoryParts.isEmpty()) {
            warnings.add("PaceSetter's Inventory answers were not available: a sync that would take in "
                    + "variants another store product sells may not be flagged.");
        }

        CatalogStatusClassifier.Result result = CatalogStatusClassifier.classify(store,
                new CatalogStatusClassifier.Supplier(catalog.productIds(), productParts, names, pricedParts,
                        inventoryParts));
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            counts.put(c.name(), 0);
        }
        for (Entry e : result.products()) {
            counts.merge(e.category().name(), 1, Integer::sum);
        }
        counts.put("NEW_AT_SUPPLIER", result.newAtSupplier().size());
        log.info("Built catalog status in {}s: {} store products {}, {} new at the supplier",
                Duration.between(started, Instant.now()).toSeconds(), store.size(), counts,
                result.newAtSupplier().size());
        return new CatalogStatusView("ready", false, Instant.now(), counts, result.products(),
                result.newAtSupplier(), result.related(), result.notImportable(), warnings, adminProducts());
    }

    private static List<String> partIds(Product product) {
        if (product.parts() == null) {
            return List.of();
        }
        return product.parts().stream().map(Product.ProductPart::partId)
                .filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList();
    }

    /** Per sellable id, the parts with a price break; an id whose read fails is simply absent. */
    private Map<String, List<String>> pricedParts(List<String> ids) {
        Map<String, List<String>> out = new ConcurrentHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(FETCH_CONCURRENCY, ids.size()));
        try {
            List<CompletableFuture<Void>> calls = new ArrayList<>();
            for (String id : ids) {
                calls.add(CompletableFuture.runAsync(() -> {
                    List<String> priced = priced(id);
                    if (!priced.isEmpty()) {
                        out.put(id, priced);
                    }
                }, pool));
            }
            calls.forEach(CompletableFuture::join);
        } finally {
            pool.shutdown();
        }
        return out;
    }

    private List<String> priced(String id) {
        try {
            Configuration config = pricing.getConfigurationAndPricing(id, sync.currency(), null, "Net", null,
                    sync.country(), sync.language());
            if (config == null || config.partPrices() == null) {
                return List.of();
            }
            return config.partPrices().stream()
                    .filter(p -> p.partId() != null && p.priceBreaks() != null && !p.priceBreaks().isEmpty())
                    .map(p -> p.partId().trim()).distinct().toList();
        } catch (RuntimeException e) {
            log.debug("catalog status: no price for {}: {}", id, e.getMessage());
            return List.of();
        }
    }

    /** Every product tagged promostandards, archived ones included. */
    List<StoreProduct> storeProducts() {
        List<StoreProduct> out = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> vars = new HashMap<>();
            if (cursor != null) {
                vars.put("cursor", cursor);
            }
            JsonNode page = gql.execute(ShopifyGraphQL.CATALOG_STATUS_PRODUCTS, vars).path("products");
            for (JsonNode n : page.path("nodes")) {
                List<String> tags = new ArrayList<>();
                n.path("tags").forEach(t -> tags.add(t.asText()));
                out.add(new StoreProduct(n.path("id").asText(null), n.path("handle").asText(null),
                        n.path("title").asText(null), n.path("status").asText(null), tags,
                        supplierIds(n), text(n.path("lastSync")), imageUrl(n), text(n.path("groupedInto"))));
            }
            JsonNode info = page.path("pageInfo");
            cursor = info.path("hasNextPage").asBoolean(false) ? info.path("endCursor").asText(null) : null;
        } while (cursor != null);
        return out;
    }

    /** {@code ps_product_ids} (a JSON list), else the single {@code ps_product_id}; canonical first. */
    private List<String> supplierIds(JsonNode n) {
        List<String> ids = new ArrayList<>();
        String single = text(n.path("psId"));
        if (single != null) {
            ids.add(single.trim());
        }
        String list = text(n.path("psIds"));
        if (list != null) {
            try {
                for (JsonNode id : objectMapper.readTree(list)) {
                    String value = id.asText("").trim();
                    if (!value.isEmpty() && ids.stream().noneMatch(value::equalsIgnoreCase)) {
                        ids.add(value);
                    }
                }
            } catch (IOException e) {
                log.debug("catalog status: unreadable ps_product_ids on {}: {}", n.path("handle").asText(), list);
            }
        }
        return ids;
    }

    private static String imageUrl(JsonNode n) {
        return text(n.path("featuredMedia").path("preview").path("image").path("url"));
    }

    /** A metafield's value, or the node's own text; null when absent or blank. */
    private static String text(JsonNode node) {
        JsonNode value = node.has("value") ? node.path("value") : node;
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String s = value.asText(null);
        return s == null || s.isBlank() ? null : s;
    }

    private boolean isStale(CatalogStatusView current) {
        if (current.builtAt() == null) {
            return true;
        }
        Duration ttl = props.ttl();
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            return false;
        }
        return current.builtAt().plus(ttl).isBefore(Instant.now());
    }

    private void persist(CatalogStatusView current) {
        if (props.cacheFile() == null || props.cacheFile().isBlank()) {
            return;
        }
        Path file = Path.of(props.cacheFile());
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            objectMapper.writeValue(file.toFile(), new Persisted(currentSource(), current));
        } catch (IOException e) {
            log.warn("Could not write catalog status cache {}: {}", file, e.getMessage());
        }
    }
}
