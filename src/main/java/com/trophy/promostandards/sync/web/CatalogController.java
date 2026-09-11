package com.trophy.promostandards.sync.web;

import com.trophy.promostandards.db.CatalogQuery;
import com.trophy.promostandards.sync.CatalogGroupIndex;
import com.trophy.promostandards.sync.CatalogSummaryService;
import com.trophy.promostandards.sync.CatalogTitleIndex;
import com.trophy.promostandards.sync.PendingDataIndex;
import com.trophy.promostandards.sync.model.PendingDataView;
import com.trophy.promostandards.sync.model.CatalogEntry;
import com.trophy.promostandards.sync.model.CatalogGroupView;
import com.trophy.promostandards.sync.model.CatalogTitleView;
import com.trophy.promostandards.sync.model.ProductDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Read API powering the catalog console table: a product list with summary figures, and full
 * per-product detail for the expandable rows. Write/sync actions live on {@code /api/sync}.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogSummaryService summaries;
    private final CatalogGroupIndex groupIndex;
    private final CatalogTitleIndex titleIndex;
    private final PendingDataIndex pendingData;

    public CatalogController(CatalogSummaryService summaries, CatalogGroupIndex groupIndex,
                             CatalogTitleIndex titleIndex, PendingDataIndex pendingData) {
        this.summaries = summaries;
        this.groupIndex = groupIndex;
        this.titleIndex = titleIndex;
        this.pendingData = pendingData;
    }

    /** Cheap list: one entry (id + imported flag) per discoverable product. */
    @GetMapping("/products")
    public List<CatalogEntry> products() {
        return summaries.listProductIds();
    }

    /**
     * One page of the catalog, searched and sorted by the database.
     *
     * <p>Separate from {@code /products} on purpose: that endpoint returns the whole (cheap) id list
     * and works with or without a database, which is what the console uses today. This one needs the
     * mirror and answers 503 without it, rather than quietly returning an unfiltered page.
     *
     * @param q      free text matched against product id, title and vendor
     * @param status {@code all} | {@code close-out} | {@code no-product-data} | {@code discontinued}
     * @param sort   {@code product-id} | {@code title} | {@code vendor}
     */
    @GetMapping("/products/search")
    public CatalogQuery.Page search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "all") String status,
            @RequestParam(required = false, defaultValue = "product-id") String sort,
            @RequestParam(required = false, defaultValue = "true") boolean asc,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size) {
        return summaries.search(new CatalogQuery.Request(q, parse(status, CatalogQuery.Status.class),
                parse(sort, CatalogQuery.Sort.class), asc, page, size));
    }

    /** Maps a kebab-case query value onto its enum constant, rejecting anything unknown with a 400. */
    private static <E extends Enum<E>> E parse(String value, Class<E> type) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown value '" + value + "' for " + type.getSimpleName()
                    + "; expected one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    /** Full variant/inventory/pricing detail for an expanded row. */
    @GetMapping("/products/{productId}")
    public ProductDetail product(@PathVariable String productId) {
        return summaries.detail(productId);
    }

    /**
     * Cached "group variants" index: families of sibling product ids the supplier split across ids.
     * The first call kicks off the (throttled, cached) background build and may return
     * {@code status=building} with no families yet; poll until {@code status=ready}.
     */
    @GetMapping("/product-groups")
    public CatalogGroupView productGroups() {
        return groupIndex.view();
    }

    /** Force a rebuild of the group index in the background; returns the current (pre-rebuild) view. */
    @PostMapping("/product-groups/refresh")
    public CatalogGroupView refreshProductGroups() {
        return groupIndex.refresh();
    }

    /**
     * Cached product name/vendor per catalog id, so the console's search box can match names without
     * a request per keystroke. Like the group index this builds in the background: the first call may
     * return {@code status=building} with no titles; poll until {@code status=ready}.
     */
    @GetMapping("/product-titles")
    public CatalogTitleView productTitles() {
        return titleIndex.view();
    }

    /** Force a rebuild of the title index in the background; returns the current (pre-rebuild) view. */
    @PostMapping("/product-titles/refresh")
    public CatalogTitleView refreshProductTitles() {
        return titleIndex.refresh();
    }

    /**
     * The catalog ids whose Inventory service answers nothing — not ready to import, so the console
     * keeps them out of "Not imported" and lists them under "Pending data". Builds in the background
     * like the title index: the first call may return {@code status=building}; poll until ready.
     */
    @GetMapping("/pending-data")
    public PendingDataView pendingData() {
        return pendingData.view();
    }

    /** Force a rebuild of the pending-data index in the background. */
    @PostMapping("/pending-data/refresh")
    public PendingDataView refreshPendingData() {
        return pendingData.refresh();
    }
}
