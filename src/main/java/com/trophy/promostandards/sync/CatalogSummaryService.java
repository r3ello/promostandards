package com.trophy.promostandards.sync;

import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.service.InventoryService;
import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.service.MediaService;
import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.service.PricingService;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.model.CatalogEntry;
import com.trophy.promostandards.sync.model.ProductDetail;
import com.trophy.promostandards.sync.model.ProductDetail.ChargeRow;
import com.trophy.promostandards.sync.model.ProductDetail.InventoryRow;
import com.trophy.promostandards.sync.model.ProductDetail.PriceBreak;
import com.trophy.promostandards.sync.model.ProductDetail.PricePart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Read model behind the catalog table.
 *
 * <p>The list is intentionally <b>cheap</b>: it discovers product ids from the supplier's sellable
 * catalog (a single upstream call) plus one Shopify "imported" lookup — it does <b>not</b> aggregate
 * every product. {@link #detail(String)} (called lazily per visible/expanded row) assembles the full
 * per-service detail for display: the raw inventory variations, the quantity price-break matrix, and
 * charges — everything the standalone service endpoints return.
 */
@Service
public class CatalogSummaryService {

    private static final Logger log = LoggerFactory.getLogger(CatalogSummaryService.class);

    private final ProductDataService productData;
    private final PricingService pricing;
    private final InventoryService inventory;
    private final MediaService media;
    private final PricingPolicy pricingPolicy;
    private final ShopifySyncService sync;
    private final ShopifyProperties shopify;
    private final SyncProperties props;

    public CatalogSummaryService(ProductDataService productData, PricingService pricing,
                                 InventoryService inventory, MediaService media, PricingPolicy pricingPolicy,
                                 ShopifySyncService sync, ShopifyProperties shopify, SyncProperties props) {
        this.productData = productData;
        this.pricing = pricing;
        this.inventory = inventory;
        this.media = media;
        this.pricingPolicy = pricingPolicy;
        this.sync = sync;
        this.shopify = shopify;
        this.props = props;
    }

    /** @return one cheap entry per discoverable product (id + Shopify imported flag). */
    public List<CatalogEntry> listProductIds() {
        Set<String> importedIds = importedIdsOrNull();
        List<CatalogEntry> entries = new ArrayList<>();
        for (String productId : discoverProductIds()) {
            entries.add(new CatalogEntry(productId, imported(importedIds, productId)));
        }
        return entries;
    }

    /** @return full per-service detail for one product (lazy per row): inventory, pricing, charges. */
    public ProductDetail detail(String productId) {
        String country = props.country();
        String language = props.language();

        Product product = productData.getProduct(productId, country, language);

        List<String> imageUrls = new ArrayList<>(new LinkedHashSet<>(
                media.getMediaContent(productId, "Image", null).stream()
                        .map(MediaContent::url).filter(u -> u != null && !u.isBlank()).toList()));

        List<InventoryRow> inventoryRows = inventory.getInventoryLevels(productId, null).parts().stream()
                .map(p -> new InventoryRow(p.partId(), p.color(), p.size(),
                        p.partDescription() != null ? p.partDescription() : p.partBrand(), p.quantityAvailable()))
                .toList();

        List<PricePart> pricingParts = pricing.getConfigurationAndPricing(
                        productId, props.currency(), null, "Net", null, country, language)
                .partPrices().stream().map(this::toPricePart).toList();

        List<ChargeRow> charges = safeCharges(productId, country, language);

        List<String> tags = product.categories() == null ? List.of() : product.categories();
        String productType = tags.isEmpty() ? null : tags.get(tags.size() - 1);

        return new ProductDetail(productId, product.productName(), product.description(),
                CatalogService.norm(product.productBrand()), productType, tags, imageUrls,
                imported(importedIdsOrNull(), productId), inventoryRows, pricingParts, charges);
    }

    private PricePart toPricePart(Configuration.PartPrice pp) {
        BigDecimal retail = pp.priceBreaks().stream()
                .min(Comparator.comparingInt(Configuration.PriceBreak::minQuantity))
                .map(b -> pricingPolicy.retailPrice(b.price(), b.listPrice()))
                .orElse(null);
        List<PriceBreak> breaks = pp.priceBreaks().stream()
                .map(b -> new PriceBreak(b.minQuantity(), b.price(), b.listPrice(), b.priceUom()))
                .toList();
        return new PricePart(pp.partId(), pp.description(), retail, breaks);
    }

    private List<ChargeRow> safeCharges(String productId, String country, String language) {
        try {
            return pricing.getAvailableCharges(productId, country, language).stream()
                    .map(c -> new ChargeRow(c.chargeId(), c.chargeName(), c.chargeType(),
                            c.priceBreaks().isEmpty() ? null : c.priceBreaks().get(0).price()))
                    .toList();
        } catch (RuntimeException e) {
            log.debug("charges unavailable for {}: {}", productId, e.getMessage());
            return List.of();
        }
    }

    /** Distinct sellable product ids — a single upstream call (the catalog's id source). */
    private List<String> discoverProductIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (ProductSellable s : productData.getProductSellable(null, true)) {
            if (s.productId() != null) {
                ids.add(s.productId());
            }
        }
        return new ArrayList<>(ids);
    }

    private Boolean imported(Set<String> importedIds, String productId) {
        return importedIds == null ? null : importedIds.contains(productId);
    }

    /** @return the set of imported supplier ids, or null when Shopify isn't connected/reachable. */
    private Set<String> importedIdsOrNull() {
        if (shopify.storeDomain() == null || shopify.storeDomain().isBlank()) {
            return null;
        }
        try {
            return new LinkedHashSet<>(sync.listImportedProductIds());
        } catch (RuntimeException e) {
            log.warn("Could not list imported products from Shopify: {}", e.getMessage());
            return null;
        }
    }
}
