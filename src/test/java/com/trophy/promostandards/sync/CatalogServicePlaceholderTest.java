package com.trophy.promostandards.sync;

import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.service.InventoryService;
import com.trophy.promostandards.media.service.MediaService;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.service.PricingService;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression for real PaceSetter data (C1925): Product Data carries a placeholder part (colour
 * "N/A", no sizes, brand "NULL") while the real colour/size lives only on the Inventory row for the
 * same part id. The union must merge them into ONE variant — the first cut imported a phantom
 * zero-stock "N/A / One Size" variant next to the real "Clear / 5.75 X 5" one.
 */
class CatalogServicePlaceholderTest {

    private static final SyncProperties PROPS = new SyncProperties("PaceSetter", "USD", "US", "en",
            SyncProperties.SkuStrategy.PART_SIZE,
            new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
            new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);

    @Test
    void placeholderPartMergesWithItsInventoryRow() {
        ProductDataService productData = mock(ProductDataService.class);
        PricingService pricing = mock(PricingService.class);
        InventoryService inventory = mock(InventoryService.class);
        MediaService media = mock(MediaService.class);

        when(productData.getProduct(eq("C1925"), any(), any())).thenReturn(new Product(
                "C1925", "Star Award on Base", "5\" x 5 x 3/4\" Star Award on Base", "NULL",
                List.of("Acrylic"),
                List.of(new Product.ProductPart("C1925", "Star Award on Base", "N/A", List.of()))));
        when(pricing.getConfigurationAndPricingWithList(eq("C1925"), any(), any(), any(), any(), any()))
                .thenReturn(new Configuration("C1925", "USD", "Net", List.of(
                        new Configuration.PartPrice("C1925", "Star Award on Base", List.of(
                                new Configuration.PriceBreak(1, new BigDecimal("58.26"), null, "BX")))),
                        List.of()));
        when(inventory.getInventoryLevels(eq("C1925"), any())).thenReturn(new InventoryLevels("C1925",
                List.of(new InventoryLevels.PartInventory("C1925", "Star Award on Base", null,
                        5, "Clear", "5.75 X 5", null, null))));
        when(media.getMediaContent(eq("C1925"), any(), any())).thenReturn(List.of());

        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        CatalogService catalog = new CatalogService(productData, pricing, inventory, media, props);

        SupplierProduct product = catalog.aggregate("C1925");

        // One physical part -> one variant, carrying the inventory row's real colour/size/stock.
        assertThat(product.variants()).hasSize(1);
        Variant v = product.variants().get(0);
        assertThat(v.color()).isEqualTo("Clear");
        assertThat(v.size()).isEqualTo("5.75 X 5");
        assertThat(v.onHand()).isEqualTo(5);
        assertThat(v.supplierNet()).isEqualByComparingTo("58.26");
        // A literal "NULL" brand is a supplier placeholder, not a vendor.
        assertThat(product.vendor()).isNull();
    }

    @Test
    void productWithoutInventoryRowsKeepsItsPlaceholderVariant() {
        ProductDataService productData = mock(ProductDataService.class);
        PricingService pricing = mock(PricingService.class);
        InventoryService inventory = mock(InventoryService.class);
        MediaService media = mock(MediaService.class);

        when(productData.getProduct(eq("C0500"), any(), any())).thenReturn(new Product(
                "C0500", "Plain Award", null, null, List.of(),
                List.of(new Product.ProductPart("C0500", "Plain Award", "N/A", List.of()))));
        when(pricing.getConfigurationAndPricingWithList(eq("C0500"), any(), any(), any(), any(), any()))
                .thenReturn(new Configuration("C0500", "USD", "Net", List.of(), List.of()));
        when(inventory.getInventoryLevels(eq("C0500"), any()))
                .thenReturn(new InventoryLevels("C0500", List.of()));
        when(media.getMediaContent(eq("C0500"), any(), any())).thenReturn(List.of());

        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        CatalogService catalog = new CatalogService(productData, pricing, inventory, media, props);

        // With no inventory rows there is nothing to merge into; the part must still yield a variant.
        SupplierProduct product = catalog.aggregate("C0500");
        assertThat(product.variants()).hasSize(1);
        assertThat(product.variants().get(0).sku()).isEqualTo("C0500");
    }

    /**
     * PaceSetter sells products its own Inventory service answers "ProductID not found" for, and its
     * Media service faults outright on the GM8xx family. Before this, either of those took the whole
     * import down — 18 products in the first full pass over the store, with complete product data,
     * prices and discounts, lost to a service that had nothing to add.
     *
     * <p>The product must come through with its variants and prices, the missing side must be left
     * <b>unknown</b> rather than zeroed ({@code onHand} null is what makes every inventory push skip
     * it), and the reason must be reported instead of swallowed.
     */
    @Test
    void survivesAServiceThatCannotAnswer() {
        ProductDataService productData = mock(ProductDataService.class);
        PricingService pricing = mock(PricingService.class);
        InventoryService inventory = mock(InventoryService.class);
        MediaService media = mock(MediaService.class);

        when(productData.getProduct(eq("G0990"), any(), any())).thenReturn(new Product(
                "G0990", "Star Tower Award", "Optic crystal star tower", "PaceSetter",
                List.of("Awards"),
                List.of(new Product.ProductPart("G0990", "Star Tower", "Clear", List.of("8 X 3")))));
        when(pricing.getConfigurationAndPricingWithList(eq("G0990"), any(), any(), any(), any(), any()))
                .thenReturn(new Configuration("G0990", "USD", "List", List.of(
                        new Configuration.PartPrice("G0990", "Star Tower", List.of(
                                new Configuration.PriceBreak(1, new BigDecimal("60.00"),
                                        new BigDecimal("100.00"), "EA")))), List.of()));
        when(inventory.getInventoryLevels(eq("G0990"), any())).thenThrow(
                new IllegalStateException("PromoStandards inventory service returned an error: "
                        + "200: ProductID not found"));
        when(media.getMediaContent(eq("G0990"), any(), any())).thenThrow(
                new IllegalStateException("getMediaContent call failed: SoapFault: Input string"));

        SupplierProduct product = new CatalogService(productData, pricing, inventory, media, PROPS)
                .aggregate("G0990");

        assertThat(product.title()).isEqualTo("Star Tower Award");
        assertThat(product.variants()).singleElement().satisfies(v -> {
            assertThat(v.sku()).isEqualTo("G0990-8 X 3");
            assertThat(v.listPrice()).isEqualByComparingTo("100.00");
            // Unknown, NOT zero: every inventory push skips a null, so real stock cannot be wiped.
            assertThat(v.onHand()).isNull();
        });
        assertThat(product.imageUrls()).isEmpty();
        assertThat(product.warnings()).hasSize(2)
                .anySatisfy(w -> assertThat(w).startsWith("Inventory:").contains("ProductID not found")
                        .contains("stock is left"))
                .anySatisfy(w -> assertThat(w).startsWith("Media:").contains("images are left"));
    }
}
