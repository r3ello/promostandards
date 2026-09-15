package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.ShopifySyncService.ImportedProduct;
import com.trophy.promostandards.sync.model.ProductGroupPreview;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Deciding, from the app, that several of the supplier's products are one product of the store.
 *
 * <p>It answers before it acts on purpose: applying a group writes identity metafields on a live
 * store and cannot be undone by writing them back, because the sync never deletes a variant. So the
 * preview has to show the variants the sync would really produce — it runs the same union — and has
 * to refuse the two groupings that quietly lose data: one under a product the store does not carry,
 * and one that empties a product still holding ids nobody asked to move.
 */
class ProductGroupServiceTest {

    private final ShopifySyncService sync = mock(ShopifySyncService.class);
    private final ProductGroupService groups = new ProductGroupService(sync);

    private static ImportedProduct store(String gid, String handle, String... ids) {
        return new ImportedProduct(gid, handle, ids[0], List.of(ids), "migration", null);
    }

    private static Variant variant(String partId, String color, Integer onHand) {
        return new Variant(partId, color, "2.5 X 1", "PS1-" + partId, new BigDecimal("4.50"),
                new BigDecimal("9.99"), onHand, List.of(), null, null);
    }

    private void supplierWouldProduce(List<Variant> variants, List<String> colorLabels,
                                      List<String> supplierIds, List<String> warnings) {
        SupplierProduct union = new SupplierProduct("CM291BS", "Leatherette Oval Keychain", null,
                null, null, List.of(), variants, List.of(), List.of(), warnings);
        when(sync.groupPreview(anyString(), any())).thenReturn(
                new ForeignProductSync.GroupPreview(union, colorLabels, supplierIds, true));
    }

    @Test
    void showsTheVariantsTheGroupedProductWouldCarry() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store("gid://shopify/Product/1", "p-1-leatherette-oval-keychain", "CM291BS"),
                store("gid://shopify/Product/2", "p-2-rose-leatherette-oval-keychain", "CM291RS")));
        supplierWouldProduce(List.of(variant("CM291BS", "Black Silver", 12), variant("CM291RS", "Rose", 0)),
                List.of("Black Silver", "Rose"), List.of("CM291BS", "CM291RS"),
                List.of("Media: nothing for CM291RS"));

        ProductGroupPreview preview = groups.preview("CM291BS", List.of("CM291RS"));

        assertThat(preview.applicable()).isTrue();
        assertThat(preview.conflicts()).isEmpty();
        assertThat(preview.parentHandle()).isEqualTo("p-1-leatherette-oval-keychain");
        assertThat(preview.variants()).extracting(ProductGroupPreview.Variant::color)
                .containsExactly("Black Silver", "Rose");
        assertThat(preview.variants()).extracting(ProductGroupPreview.Variant::supplierProductId)
                .containsExactly("CM291BS", "CM291RS");
        assertThat(preview.variants().get(1).onHand()).isZero();
        // The store product that would be emptied, named so it can be archived afterwards.
        assertThat(preview.absorbed()).singleElement().satisfies(a ->
                assertThat(a.handle()).isEqualTo("p-2-rose-leatherette-oval-keychain"));
        // What the supplier could not answer while building it is carried, not swallowed.
        assertThat(preview.warnings()).containsExactly("Media: nothing for CM291RS");
    }

    /** Grouping is for what the store already sells: creating products stays off. */
    @Test
    void refusesAParentTheStoreDoesNotCarry() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store("gid://shopify/Product/2", "p-2-rose", "CM291RS")));

        ProductGroupPreview preview = groups.preview("CM291BS", List.of("CM291RS"));

        assertThat(preview.applicable()).isFalse();
        assertThat(preview.conflicts()).singleElement().asString().contains("CM291BS");
        // And it costs no supplier read: the answer was settled from the store index.
        verify(sync, never()).groupPreview(anyString(), any());
    }

    /**
     * The absorbed product loses its identity metafields, so an id of its that nobody asked to move
     * would stop being synced by anything — silently. Name it and refuse.
     */
    @Test
    void refusesToEmptyAProductStillHoldingIdsNobodyAskedFor() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store("gid://shopify/Product/1", "p-1-keychain", "CM291BS"),
                store("gid://shopify/Product/2", "p-2-rose", "CM291RS", "CM291XX")));
        supplierWouldProduce(List.of(variant("CM291BS", "Black Silver", 12)),
                List.of("Black Silver"), List.of("CM291BS"), List.of());

        ProductGroupPreview preview = groups.preview("CM291BS", List.of("CM291RS"));

        assertThat(preview.applicable()).isFalse();
        assertThat(preview.conflicts()).singleElement().asString()
                .contains("p-2-rose").contains("CM291XX");
        assertThat(preview.absorbed()).singleElement().satisfies(a ->
                assertThat(a.notRequested()).containsExactly("CM291XX"));
    }
}
