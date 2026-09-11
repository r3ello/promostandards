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

import com.trophy.promostandards.common.PromoStandardsClientException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
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
                List.of(new Product.ProductPart("C1925", "Star Award on Base", "N/A", List.of(), null, null))));
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
                List.of(new Product.ProductPart("C0500", "Plain Award", "N/A", List.of(), null, null))));
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
     * CM330: eight colours, each a part with its own price, every one "N/A" with no size and no
     * Inventory row. Keyed by (colour, size) they all fell on the same empty key and the store got
     * one variant instead of eight (2026-09-10). Each part id must stay a variant of its own.
     */
    @Test
    void partsWithoutColourSizeOrInventoryStayDistinct() {
        ProductDataService productData = mock(ProductDataService.class);
        PricingService pricing = mock(PricingService.class);
        InventoryService inventory = mock(InventoryService.class);
        MediaService media = mock(MediaService.class);
        String description = "2 1/2\" x 1 1/2\" Leatherette Bottle Opener Keychain; Laser Engraved";
        when(productData.getProduct(eq("CM330"), any(), any())).thenReturn(new Product(
                "CM330", "Leatherette Bottle Opener Keychain", description, "NULL", List.of(),
                List.of(new Product.ProductPart("CM330BS", description, "N/A", List.of(), null, null),
                        new Product.ProductPart("CM330DB", description, "N/A", List.of(), null, null),
                        new Product.ProductPart("CM330TL", description, "N/A", List.of(), null, null))));
        when(pricing.getConfigurationAndPricingWithList(eq("CM330"), any(), any(), any(), any(), any()))
                .thenReturn(new Configuration("CM330", "USD", "Net", List.of(
                        price("CM330BS", "10.00"), price("CM330DB", "11.00"), price("CM330TL", "12.00")),
                        List.of()));
        when(inventory.getInventoryLevels(eq("CM330"), any()))
                .thenThrow(new PromoStandardsClientException("ProductID not found"));
        when(media.getMediaContent(eq("CM330"), any(), any())).thenReturn(List.of());

        SupplierProduct product = new CatalogService(productData, pricing, inventory, media, PROPS)
                .aggregate("CM330");

        assertThat(product.variants()).extracting(Variant::supplierPartId)
                .containsExactly("CM330BS", "CM330DB", "CM330TL");
        assertThat(product.variants()).extracting(v -> v.supplierNet().toPlainString())
                .containsExactly("10.00", "11.00", "12.00");
        // One description for all three says nothing: no label, and no colour invented either.
        assertThat(product.variants()).allSatisfy(v -> {
            assertThat(v.color()).isNull();
            assertThat(v.label()).isNull();
        });
        // So the store shows the part codes.
        assertThat(VariantOptions.colorLabels(product.variants())).containsExactly("BS", "DB", "TL");
    }

    /** Where descriptions do differ, the words that differ are the name: a colour, a size, a finish. */
    @Test
    void namesPartsByWhatTheirDescriptionsDoNotShare() {
        Map<String, String> tumblers = new LinkedHashMap<>();
        tumblers.put("CM711BK", "3 3/8\" x 6 7/8\" Polar Camel 20 oz. Ringneck Tumbler; Black; Laser Engraved; Gift Personalizations $4.00(V)");
        tumblers.put("CM711BL", "3 3/8\" x 6 7/8\" Polar Camel 20 oz. Ringneck Tumbler; Blue; Laser Engraved; Gift Personalizations $4.00(V)");
        tumblers.put("CM711DB", "3 3/8\" x 6 7/8\" Polar Camel 20 oz. Ringneck Tumbler; Dark Blue; Laser Engraved; Gift Personalizations $4.00(V)");
        tumblers.put("CM711BKRG", "Polar Camel 20 oz. Ringneck Tumbler Black/Rose Gold-Laser Imprint");
        assertThat(CatalogService.distinguishingLabels(tumblers)).containsExactly(
                entry("CM711BK", "Black"), entry("CM711BL", "Blue"), entry("CM711DB", "Dark Blue"),
                entry("CM711BKRG", "Black/Rose Gold-Laser Imprint"));

        Map<String, String> towers = new LinkedHashMap<>();
        towers.put("GM792A", "2 1/2\" x 7 3/4\" x 2 1/2\" Tower of Facets, Small  Sandblasting; Free Personal");
        towers.put("GM792B", "2 3/4\" x 8 1/4\" x 2 3/4\" Tower of Facets, Med  Sandblasting; Free Personal");
        towers.put("GM792C", "2 3/4\" x 9 1/4\" x 2 3/4\" Tower of Facets, Large  Sandblasting; Free Personal");
        assertThat(CatalogService.distinguishingLabels(towers)).containsExactly(
                entry("GM792A", "Small"), entry("GM792B", "Med"), entry("GM792C", "Large"));

        Map<String, String> plaques = new LinkedHashMap<>();
        plaques.put("C021ABEF", "8\" x 10\" Ebony Finish Plaque with Marble Mist; Laser Engraved");
        plaques.put("C021ABWF", "8\" x 10\" Walnut Finish Plaque with Marble Mist; Laser Engraved");
        assertThat(CatalogService.distinguishingLabels(plaques)).containsExactly(
                entry("C021ABEF", "Ebony"), entry("C021ABWF", "Walnut"));

        assertThat(CatalogService.distinguishingLabels(Map.of("CM330BS", "Same words", "CM330DB", "Same words")))
                .isEmpty();

        // What differs is a whole phrase (C071A): that is a description, not a name — the code is
        // clearer. And punctuation ("#", "-") is never a word of a name.
        Map<String, String> boards = new LinkedHashMap<>();
        boards.put("C071AGOEF", "Florentine # Gold Edge Plate on Ebony Board");
        boards.put("C071ASWF", "Florentine - Silver");
        assertThat(CatalogService.distinguishingLabels(boards)).containsExactly(entry("C071ASWF", "Silver"));
    }

    private static Configuration.PartPrice price(String partId, String amount) {
        return new Configuration.PartPrice(partId, partId, List.of(
                new Configuration.PriceBreak(1, new BigDecimal(amount), null, "EA")));
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
                List.of(new Product.ProductPart("G0990", "Star Tower", "Clear", List.of("8 X 3"), null, null))));
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

    /**
     * PaceSetter never sets {@code partId} on its media, but it serves each colour as a <b>product of
     * its own</b> with one photo: {@code getMediaContent(CM373LB)} answers {@code cm373lb.jpg}. So the
     * image a call returns belongs to the variant of the id that was asked for — and to that one only,
     * or CM373BS's photo would be published as all eleven colours of the family.
     */
    @Test
    void givesTheProductsOwnPhotoToItsOwnVariant() {
        ProductDataService productData = mock(ProductDataService.class);
        PricingService pricing = mock(PricingService.class);
        InventoryService inventory = mock(InventoryService.class);
        MediaService media = mock(MediaService.class);

        when(productData.getProduct(eq("CM373BS"), any(), any())).thenReturn(new Product(
                "CM373BS", "Leatherette on Steel Shot Glass", "desc", "PaceSetter", List.of("Drinkware"),
                List.of(new Product.ProductPart("CM373BS", "Black Silver", "Black Silver", List.of(), null, null),
                        new Product.ProductPart("CM373LB", "Light Brown", "Light Brown", List.of(), null, null))));
        when(pricing.getConfigurationAndPricingWithList(eq("CM373BS"), any(), any(), any(), any(), any()))
                .thenReturn(new Configuration("CM373BS", "USD", "List", List.of(
                        new Configuration.PartPrice("CM373BS", "Black Silver", List.of(
                                new Configuration.PriceBreak(25, new BigDecimal("12.36"),
                                        new BigDecimal("19.99"), "EA")))), List.of()));
        when(inventory.getInventoryLevels(eq("CM373BS"), any())).thenReturn(
                new InventoryLevels("CM373BS", List.of()));
        // One image, no partId — exactly what the supplier sends.
        when(media.getMediaContent(eq("CM373BS"), any(), any())).thenReturn(List.of(
                new com.trophy.promostandards.media.model.MediaContent("CM373BS", null, "Image",
                        "https://pacesetterawards.com/Images/cm373bs.jpg", "cm373bs.jpg", null,
                        null, null, null, null)));

        SupplierProduct product = new CatalogService(productData, pricing, inventory, media, PROPS)
                .aggregate("CM373BS");

        assertThat(product.imageUrls()).containsExactly("https://pacesetterawards.com/Images/cm373bs.jpg");
        assertThat(product.variants()).anySatisfy(v -> {
            assertThat(v.supplierPartId()).isEqualTo("CM373BS");
            assertThat(v.imageUrls()).containsExactly("https://pacesetterawards.com/Images/cm373bs.jpg");
        });
        assertThat(product.variants()).anySatisfy(v -> {
            assertThat(v.supplierPartId()).isEqualTo("CM373LB");
            assertThat(v.imageUrls()).isEmpty();     // its photo lives under its own product id
        });
    }
}
