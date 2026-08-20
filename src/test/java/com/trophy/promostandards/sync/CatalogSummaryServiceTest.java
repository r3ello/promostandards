package com.trophy.promostandards.sync;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.PromoStandardsNotFoundException;
import com.trophy.promostandards.db.CatalogRow;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.sync.model.CatalogEntry;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryLevels.PartInventory;
import com.trophy.promostandards.inventory.service.InventoryService;
import com.trophy.promostandards.media.service.MediaService;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.service.PricingService;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.ProductDetail;
import org.junit.jupiter.api.BeforeEach;

import static com.trophy.promostandards.sync.CatalogTestSupport.providerOf;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalog detail read: partial-data tolerance and caching. Both exist because of how the console
 * hammers this endpoint (one call per visible row, re-triggered on every page/filter change) and how
 * inconsistent the supplier's own services are with each other.
 */
class CatalogSummaryServiceTest {

    private ProductDataService productData;
    private PricingService pricing;
    private InventoryService inventory;
    private MediaService media;
    private ShopifySyncService sync;
    private CatalogSummaryService service;
    private CatalogTestSupport.FakeCatalogStore store;

    @BeforeEach
    void setUp() {
        store = new CatalogTestSupport.FakeCatalogStore();
        productData = mock(ProductDataService.class);
        pricing = mock(PricingService.class);
        inventory = mock(InventoryService.class);
        media = mock(MediaService.class);
        sync = mock(ShopifySyncService.class);

        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(),
                new SyncProperties.SupplierMetaobject("promo_standard_supplier", "pace-setter"));
        ShopifyProperties shopify = new ShopifyProperties("", "", "", "", "2026-04", "");

        service = new CatalogSummaryService(productData, pricing, inventory, media,
                new PricingPolicy(props), sync, shopify, props,
                new CatalogCacheProperties(Duration.ofMinutes(15), Duration.ofHours(1), 100, 4),
                providerOf(store));
    }

    /** Nothing anywhere but the Product Data service: the id exists, its marketing record doesn't. */
    private void supplierHasInventoryOnly(String productId) {
        when(productData.getProduct(eq(productId), anyString(), anyString()))
                .thenThrow(new PromoStandardsNotFoundException(
                        "getProduct returned no product for productId=" + productId));
        when(inventory.getInventoryLevels(eq(productId), any())).thenReturn(new InventoryLevels(productId,
                List.of(new PartInventory(productId + "-CLR", "Clear acrylic", null, 42,
                        "Clear", "5.75 X 5", null, null))));
        when(media.getMediaContent(eq(productId), anyString(), any())).thenReturn(List.of());
        when(pricing.getConfigurationAndPricingWithList(eq(productId), anyString(), any(), any(),
                anyString(), anyString()))
                .thenReturn(new Configuration(productId, "USD", "Net", List.of(), List.of()));
        when(pricing.getAvailableCharges(eq(productId), anyString(), anyString())).thenReturn(List.of());
    }

    /**
     * The GI840 case: PaceSetter's sellable catalog lists ids its Product Data service has no record
     * for. That used to 502 the whole row even though Inventory had the goods.
     */
    @Test
    void servesTheRowWhenProductDataHasNoRecordForTheId() {
        supplierHasInventoryOnly("GI840");

        ProductDetail detail = service.detail("GI840");

        assertThat(detail.productDataMissing()).isTrue();
        assertThat(detail.title()).isEqualTo("GI840");          // falls back to the id
        assertThat(detail.inventory()).singleElement()
                .satisfies(row -> assertThat(row.onHand()).isEqualTo(42));
        assertThat(detail.warnings()).anySatisfy(w -> assertThat(w).contains("Product Data"));
    }

    /** A service being down is reported, not silently rendered as an empty catalog entry. */
    @Test
    void reportsAFailingServiceAsAWarningInsteadOfFailingTheRow() {
        supplierHasInventoryOnly("GI840");
        when(media.getMediaContent(eq("GI840"), anyString(), any()))
                .thenThrow(new PromoStandardsClientException("getMediaContent call failed: timeout"));

        ProductDetail detail = service.detail("GI840");

        assertThat(detail.imageUrls()).isEmpty();
        assertThat(detail.warnings()).anySatisfy(w -> assertThat(w).contains("Media").contains("timeout"));
    }

    /** Only a product no service knows anything about is a 404 — a genuinely unknown id. */
    @Test
    void failsWithNotFoundOnlyWhenEveryServiceComesUpEmpty() {
        String productId = "GHOST-1";
        when(productData.getProduct(eq(productId), anyString(), anyString()))
                .thenThrow(new PromoStandardsNotFoundException("no product"));
        when(inventory.getInventoryLevels(eq(productId), any()))
                .thenReturn(new InventoryLevels(productId, List.of()));
        when(media.getMediaContent(eq(productId), anyString(), any())).thenReturn(List.of());
        when(pricing.getConfigurationAndPricingWithList(eq(productId), anyString(), any(), any(),
                anyString(), anyString()))
                .thenReturn(new Configuration(productId, "USD", "Net", List.of(), List.of()));
        when(pricing.getAvailableCharges(eq(productId), anyString(), anyString())).thenReturn(List.of());

        assertThatThrownBy(() -> service.detail(productId))
                .isInstanceOf(PromoStandardsNotFoundException.class)
                .hasMessageContaining("GHOST-1");
    }

    /**
     * The console asks for the same detail repeatedly (re-render, re-filter, reload). Without the TTL
     * cache each of those replayed all five SOAP calls.
     */
    @Test
    void cachesTheAssembledDetailSoRepeatedReadsCostOneSupplierPass() {
        supplierHasInventoryOnly("GI840");

        service.detail("GI840");
        service.detail("GI840");
        service.detail("GI840");

        verify(productData, times(1)).getProduct(eq("GI840"), anyString(), anyString());
        verify(inventory, times(1)).getInventoryLevels(eq("GI840"), any());
        verify(pricing, times(1)).getConfigurationAndPricingWithList(eq("GI840"), anyString(), any(),
                any(), anyString(), anyString());
    }

    // --- catalog mirror ------------------------------------------------------------------------

    /** Sellable ids + close-out flags from the supplier, for the no-mirror path. */
    private void supplierCatalogOf(String... ids) {
        when(productData.getProductSellable(null, true)).thenReturn(
                List.of(ids).stream().map(id -> new ProductSellable(id, id + "-x", true)).toList());
        when(productData.getProductCloseOut()).thenReturn(List.of());
    }

    /** With a populated mirror the list is one database read: no getProductSellable, no close-out call. */
    @Test
    void servesTheCatalogListFromTheMirrorWhenItIsPopulated() {
        store.saveCatalog(List.of(
                new CatalogRow("A1", "Crystal Award", "PaceSetter", "Awards", false, true, false),
                new CatalogRow("GI840", null, null, null, true, true, true)));

        List<CatalogEntry> entries = service.listProductIds();

        assertThat(entries).extracting(CatalogEntry::productId).containsExactly("A1", "GI840");
        assertThat(entries).anySatisfy(e -> {
            assertThat(e.productId()).isEqualTo("GI840");
            assertThat(e.closeOut()).isTrue();
        });
        verify(productData, never()).getProductSellable(null, true);
    }

    /**
     * A database outage must degrade the console, not break it: the mirror is a cache of the
     * supplier, so an unreachable one simply means asking the supplier again.
     */
    @Test
    void fallsBackToTheSupplierWhenTheMirrorIsUnreachable() {
        store.saveCatalog(List.of(
                new CatalogRow("A1", "Crystal Award", "PaceSetter", null, false, true, false)));
        store.failing = true;
        supplierCatalogOf("A1", "A2");

        List<CatalogEntry> entries = service.listProductIds();

        assertThat(entries).extracting(CatalogEntry::productId).containsExactly("A1", "A2");
        verify(productData).getProductSellable(null, true);
    }

    /** An empty mirror is not an empty catalog — it means nothing has been scanned into it yet. */
    @Test
    void fallsBackToTheSupplierWhileTheMirrorIsStillEmpty() {
        supplierCatalogOf("A1");

        assertThat(service.listProductIds()).extracting(CatalogEntry::productId).containsExactly("A1");
    }

    /** The Shopify flag must stay live even on a cache hit — it changes the row's action button. */
    @Test
    void stampsTheImportedFlagFreshOnTopOfACachedDetail() {
        supplierHasInventoryOnly("GI840");
        when(sync.isImported("GI840")).thenReturn(false, true);

        assertThat(service.detail("GI840").imported()).isFalse();
        assertThat(service.detail("GI840").imported()).isTrue();
    }
}
