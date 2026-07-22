package com.trophy.promostandards.sync.web;

import com.trophy.promostandards.sync.CatalogSummaryService;
import com.trophy.promostandards.sync.model.CatalogEntry;
import com.trophy.promostandards.sync.model.ProductDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read API powering the catalog console table: a product list with summary figures, and full
 * per-product detail for the expandable rows. Write/sync actions live on {@code /api/sync}.
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogSummaryService summaries;

    public CatalogController(CatalogSummaryService summaries) {
        this.summaries = summaries;
    }

    /** Cheap list: one entry (id + imported flag) per discoverable product. */
    @GetMapping("/products")
    public List<CatalogEntry> products() {
        return summaries.listProductIds();
    }

    /** Full variant/inventory/pricing detail for an expanded row. */
    @GetMapping("/products/{productId}")
    public ProductDetail product(@PathVariable String productId) {
        return summaries.detail(productId);
    }
}
