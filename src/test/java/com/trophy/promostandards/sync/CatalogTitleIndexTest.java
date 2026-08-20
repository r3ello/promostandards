package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.common.PromoStandardsNotFoundException;
import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.db.CatalogRow;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.CatalogTitleView.CatalogTitle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.trophy.promostandards.sync.CatalogTestSupport.providerOf;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The title index backing name search in the console, and the scan sharing that keeps it from
 * doubling the supplier load the group index already carries.
 */
class CatalogTitleIndexTest {

    /** Stub-mode properties: the cache fingerprint is "<supplier>/<mode>". */
    private static final PromoStandardsProperties PROMO = new PromoStandardsProperties();

    private static final SyncProperties SYNC = new SyncProperties("PaceSetter", "USD", "US", "en",
            SyncProperties.SkuStrategy.PART_SIZE,
            new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
            new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);

    private static final CatalogTitleProperties PROPS =
            new CatalogTitleProperties(true, "", Duration.ZERO); // blank cache-file -> no disk I/O

    private ProductDataService catalogOf(String... ids) {
        ProductDataService pd = mock(ProductDataService.class);
        when(pd.getProductSellable(null, true)).thenReturn(
                List.of(ids).stream().map(id -> new ProductSellable(id, id + "-x", true)).toList());
        for (String id : ids) {
            when(pd.getProduct(eq(id), any(), any())).thenReturn(
                    new Product(id, "Name " + id, null, "NULL", List.of(), List.of(), List.of()));
        }
        return pd;
    }

    @Test
    void indexesNameAndVendorPerProductId() {
        ProductDataService pd = catalogOf("A1", "A2");
        when(pd.getProduct(eq("A2"), any(), any())).thenReturn(
                new Product("A2", "Crystal Award", null, "Trophy Apparel", List.of(), List.of(), List.of()));

        List<CatalogTitle> titles = new CatalogTitleIndex(new SupplierProductScan(pd, SYNC), PROPS,
                new ObjectMapper(), pd, providerOf(null), SYNC, PROMO).buildNow().titles();

        assertThat(titles).extracting(CatalogTitle::productId).containsExactlyInAnyOrder("A1", "A2");
        assertThat(titles).anySatisfy(t -> {
            assertThat(t.productId()).isEqualTo("A2");
            assertThat(t.title()).isEqualTo("Crystal Award");
            assertThat(t.vendor()).isEqualTo("Trophy Apparel");
        });
        // "NULL" is a PaceSetter placeholder, not a brand.
        assertThat(titles).anySatisfy(t -> {
            assertThat(t.productId()).isEqualTo("A1");
            assertThat(t.vendor()).isNull();
        });
    }

    /** An id the supplier lists but has no record for (the GI840 shape) is simply not searchable. */
    @Test
    void skipsIdsWithNoProductRecord() {
        ProductDataService pd = catalogOf("A1", "GI840");
        when(pd.getProduct(eq("GI840"), any(), any()))
                .thenThrow(new PromoStandardsNotFoundException("no product"));

        List<CatalogTitle> titles = new CatalogTitleIndex(new SupplierProductScan(pd, SYNC), PROPS,
                new ObjectMapper(), pd, providerOf(null), SYNC, PROMO).buildNow().titles();

        assertThat(titles).extracting(CatalogTitle::productId).containsExactly("A1");
    }

    /**
     * The mirror keeps what the title index drops: ids the supplier lists but has no record for. They
     * are still sellable and still shown in the console, flagged rather than hidden.
     */
    @Test
    void writesEveryScannedIdToTheMirrorIncludingOnesWithNoProductRecord() {
        ProductDataService pd = catalogOf("A1", "GI840");
        when(pd.getProduct(eq("GI840"), any(), any()))
                .thenThrow(new PromoStandardsNotFoundException("no product"));
        when(pd.getProductCloseOut()).thenReturn(List.of(new ProductCloseOut("gi840", "gi840-x")));
        CatalogTestSupport.FakeCatalogStore store = new CatalogTestSupport.FakeCatalogStore();

        new CatalogTitleIndex(new SupplierProductScan(pd, SYNC), PROPS, new ObjectMapper(), pd,
                providerOf(store), SYNC, PROMO).buildNow();

        assertThat(store.saved()).extracting(CatalogRow::productId).containsExactly("A1", "GI840");
        assertThat(store.saved()).anySatisfy(row -> {
            assertThat(row.productId()).isEqualTo("GI840");
            assertThat(row.productDataMissing()).isTrue();
            assertThat(row.title()).isNull();
            assertThat(row.closeOut()).isTrue();      // matched case-insensitively against "gi840"
            assertThat(row.sellable()).isTrue();
        });
    }

    /** A failed mirror write must not take the in-memory index down with it. */
    @Test
    void stillBuildsTheIndexWhenTheMirrorCannotBeWritten() {
        ProductDataService pd = catalogOf("A1");
        when(pd.getProductCloseOut()).thenThrow(new IllegalStateException("upstream down"));
        CatalogTestSupport.FakeCatalogStore store = new CatalogTestSupport.FakeCatalogStore();
        store.failing = true;

        List<CatalogTitle> titles = new CatalogTitleIndex(new SupplierProductScan(pd, SYNC), PROPS,
                new ObjectMapper(), pd, providerOf(store), SYNC, PROMO).buildNow().titles();

        assertThat(titles).extracting(CatalogTitle::productId).containsExactly("A1");
    }

    /**
     * A cache file written while running against the stubs must not be served to a live run.
     *
     * <p>This bit for real: a developer run in stub mode left four fake products in
     * {@code data/catalog-titles.json}, and the next run against PaceSetter loaded them and reported
     * {@code status=ready, count=4} — the TTL alone said the file was fresh, so the live catalog was
     * never fetched and the console showed a supplier catalog of four invented products.
     */
    @Test
    void ignoresACacheFileBuiltAgainstADifferentSource(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("catalog-titles.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mapper.writeValue(cache.toFile(), new CatalogTitleIndex.Persisted("PaceSetter/stub",
                Instant.now(), List.of(new CatalogTitle("TROPHY-MD", "Classic Trophy Cup", null))));
        CatalogTitleProperties props = new CatalogTitleProperties(true, cache.toString(),
                Duration.ofHours(24));

        PromoStandardsProperties soap = new PromoStandardsProperties();
        soap.getProductData().setMode("soap");
        CatalogTitleIndex live = new CatalogTitleIndex(new SupplierProductScan(catalogOf("A1"), SYNC),
                props, mapper, catalogOf("A1"), providerOf(null), SYNC, soap);
        live.loadFromDisk();
        assertThat(live.view().titles()).as("stub cache served to a soap run").isEmpty();

        // The same file IS reused when the run matches what produced it.
        CatalogTitleIndex stub = new CatalogTitleIndex(new SupplierProductScan(catalogOf("A1"), SYNC),
                props, mapper, catalogOf("A1"), providerOf(null), SYNC, PROMO);
        stub.loadFromDisk();
        assertThat(stub.view().titles()).extracting(CatalogTitle::productId).containsExactly("TROPHY-MD");
    }

    /**
     * Both catalog-wide indexes need every product record and are triggered by the same console
     * load. Sharing the scan is what keeps that from being two full passes at the supplier.
     */
    @Test
    void sharesOneCatalogPassWithTheGroupIndex() {
        ProductDataService pd = catalogOf("A1", "A2", "A3");
        SupplierProductScan scan = new SupplierProductScan(pd, SYNC);
        ShopifySyncService noStore = mock(ShopifySyncService.class);
        when(noStore.importedProductsOrEmpty()).thenReturn(List.of());

        new CatalogTitleIndex(scan, PROPS, new ObjectMapper(), pd, providerOf(null), SYNC, PROMO).buildNow();
        new CatalogGroupIndex(scan, new CatalogGroupProperties(true, "", Duration.ZERO),
                new ObjectMapper(), noStore, providerOf(null), SYNC, PROMO).buildNow();

        verify(pd, times(1)).getProduct(eq("A1"), any(), any());
        verify(pd, times(1)).getProductSellable(null, true);
    }
}
