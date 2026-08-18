package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.Product.RelatedProduct;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.ProductGroup;
import org.junit.jupiter.api.Test;

import static com.trophy.promostandards.sync.CatalogTestSupport.providerOf;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CatalogGroupIndex#buildNow()}: the union-find over Common Grouping links
 * merged with the store's {@code ps_product_ids} families. Uses a mocked {@link ProductDataService}
 * and {@link ShopifySyncService} (behind the shared {@link SupplierProductScan}); no async build, no
 * disk cache (empty cache-file).
 */
class CatalogGroupIndexTest {

    private static final SyncProperties SYNC = new SyncProperties("PaceSetter", "USD", "US", "en",
            SyncProperties.SkuStrategy.PART_SIZE,
            new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
            new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);

    private static final CatalogGroupProperties PROPS =
            new CatalogGroupProperties(true, "", Duration.ZERO); // blank cache-file -> no disk I/O

    /** Shopify side with no tagged products (unconfigured store behaves the same). */
    private static ShopifySyncService noStore() {
        ShopifySyncService shopifySync = mock(ShopifySyncService.class);
        when(shopifySync.importedProductsOrEmpty()).thenReturn(List.of());
        return shopifySync;
    }

    @Test
    void unionsCommonGroupingSiblingsIntoOneFamily() {
        ProductDataService pd = mock(ProductDataService.class);
        when(pd.getProductSellable(null, true)).thenReturn(List.of(
                new ProductSellable("A1", "A1-x", true),
                new ProductSellable("A2", "A2-x", true),
                new ProductSellable("A3", "A3-x", true),
                new ProductSellable("B1", "B1-x", true)));
        // A1<->A2<->A3 are one product (Common Grouping); a Substitute link and an out-of-catalog
        // link must NOT pull anything into the family. B1 stays standalone.
        when(pd.getProduct(eq("A1"), any(), any())).thenReturn(product("A1",
                new RelatedProduct("Common Grouping", "A2", null),
                new RelatedProduct("Common Grouping", "ZZ", null))); // ZZ not in catalog -> ignored
        when(pd.getProduct(eq("A2"), any(), any())).thenReturn(product("A2",
                new RelatedProduct("Common Grouping", "A3", null),
                new RelatedProduct("Substitute", "B1", null)));      // Substitute -> ignored
        when(pd.getProduct(eq("A3"), any(), any())).thenReturn(product("A3"));
        when(pd.getProduct(eq("B1"), any(), any())).thenReturn(product("B1"));

        CatalogGroupIndex index = new CatalogGroupIndex(new SupplierProductScan(pd, SYNC), PROPS, new ObjectMapper(), noStore(), providerOf(null));
        List<ProductGroup> groups = index.buildNow().groups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).primaryId()).isEqualTo("A1");
        assertThat(groups.get(0).memberIds()).containsExactly("A1", "A2", "A3");
    }

    @Test
    void noFamiliesWhenNoCommonGrouping() {
        ProductDataService pd = mock(ProductDataService.class);
        when(pd.getProductSellable(null, true)).thenReturn(List.of(
                new ProductSellable("A1", "A1-x", true),
                new ProductSellable("A2", "A2-x", true)));
        when(pd.getProduct(any(), any(), any())).thenReturn(product("x"));

        CatalogGroupIndex index = new CatalogGroupIndex(new SupplierProductScan(pd, SYNC), PROPS, new ObjectMapper(), noStore(), providerOf(null));

        assertThat(index.buildNow().groups()).isEmpty();
    }

    @Test
    void groupsMigratedFamiliesFromStorePsProductIds() {
        // PaceSetter publishes no Common Grouping: every getProduct returns no related products.
        ProductDataService pd = mock(ProductDataService.class);
        when(pd.getProductSellable(null, true)).thenReturn(List.of(
                new ProductSellable("A1", "A1-x", true),
                new ProductSellable("A2", "A2-x", true),
                new ProductSellable("B1", "B1-x", true)));
        when(pd.getProduct(any(), any(), any())).thenReturn(product("x"));

        // One migrated store product whose ps_product_ids list covers A1+A2 (values lower-cased to
        // prove case-insensitive matching) plus an id that is not in the sellable catalog.
        ShopifySyncService shopifySync = mock(ShopifySyncService.class);
        when(shopifySync.importedProductsOrEmpty()).thenReturn(List.of(
                new ShopifySyncService.ImportedProduct("gid://shopify/Product/900", "p-8123-x",
                        "a1", List.of("a1", "a2", "ZZ"), "migration")));

        CatalogGroupIndex index = new CatalogGroupIndex(new SupplierProductScan(pd, SYNC), PROPS, new ObjectMapper(), shopifySync, providerOf(null));
        List<ProductGroup> groups = index.buildNow().groups();

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).memberIds()).containsExactly("A1", "A2"); // catalog casing, no ZZ
    }

    private static Product product(String id, RelatedProduct... related) {
        return new Product(id, "Name " + id, null, null, List.of(), List.of(), List.of(related));
    }
}
