package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.sync.ShopifySyncService.ImportedProduct;
import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.model.ProductGroupPreview;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
 * to refuse the groupings that quietly lose data. Applying refuses exactly the same ones, and writes
 * in an order where stopping half-way can always be finished by applying again.
 */
class ProductGroupServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PARENT = "gid://shopify/Product/1";
    private static final String ROSE = "gid://shopify/Product/2";

    private final ShopifySyncService sync = mock(ShopifySyncService.class);
    private final ShopifyGraphQLClient gql = mock(ShopifyGraphQLClient.class);
    private final ProductGroupService groups = new ProductGroupService(sync, gql, MAPPER);

    @BeforeEach
    void storeAcceptsEveryWrite() {
        when(gql.execute(anyString(), any())).thenReturn(json("""
                {"metafieldsSet":{"userErrors":[]},"productUpdate":{"userErrors":[]},
                 "metafieldsDelete":{"userErrors":[]}}"""));
        // A migrated product: its lone legacy variant under Shopify's default option.
        when(sync.findByHandle(anyString())).thenReturn(storeProduct("Title"));
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode storeProduct(String option) {
        return json("{\"id\":\"" + PARENT + "\",\"options\":[{\"name\":\"" + option + "\"}],"
                + "\"variants\":{\"nodes\":[{\"id\":\"gid://shopify/ProductVariant/1\"}]}}");
    }

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

    /** The keychain PaceSetter sells per colour, and the store sold as two products. */
    private void keychainInTwoProducts() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(PARENT, "p-1-leatherette-oval-keychain", "CM291BS"),
                store(ROSE, "p-2-rose-leatherette-oval-keychain", "CM291RS")));
        supplierWouldProduce(List.of(variant("CM291BS", "Black Silver", 12), variant("CM291RS", "Rose", 0)),
                List.of("Black Silver", "Rose"), List.of("CM291BS", "CM291RS"), List.of());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sent(String document, String variable) {
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(gql).execute(eq(document), vars.capture());
        return (List<Map<String, Object>>) vars.getValue().get(variable);
    }

    // ---------------------------------------------------------------- preview

    @Test
    void showsTheVariantsTheGroupedProductWouldCarry() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(PARENT, "p-1-leatherette-oval-keychain", "CM291BS"),
                store(ROSE, "p-2-rose-leatherette-oval-keychain", "CM291RS")));
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
        verify(gql, never()).execute(anyString(), any());
    }

    /**
     * A parent that already covers several ids keeps them: the union — and the list it will write —
     * is over all of them, not only the ones asked for now, or the preview would under-report.
     */
    @Test
    void unionsOverTheIdsTheParentAlreadyCovers() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(PARENT, "p-1-keychain", "CM291BS", "CM291GS"),
                store(ROSE, "p-2-rose", "CM291RS")));
        supplierWouldProduce(List.of(variant("CM291BS", "Black Silver", 12)), List.of("Black Silver"),
                List.of("CM291BS"), List.of());

        ProductGroupPreview preview = groups.preview("CM291BS", List.of("CM291RS"));

        assertThat(preview.supplierIds()).containsExactly("CM291BS", "CM291GS", "CM291RS");
        verify(sync).groupPreview("CM291BS", List.of("CM291BS", "CM291GS", "CM291RS"));
        // Ids no variant came in under are named: a dead id would otherwise ride along unnoticed.
        assertThat(preview.warnings()).hasSize(2).anySatisfy(w -> assertThat(w).startsWith("CM291GS"));
    }

    /** Grouping is for what the store already sells: creating products stays off. */
    @Test
    void refusesAParentTheStoreDoesNotCarry() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(ROSE, "p-2-rose", "CM291RS")));

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
                store(PARENT, "p-1-keychain", "CM291BS"),
                store(ROSE, "p-2-rose", "CM291RS", "CM291XX")));
        supplierWouldProduce(List.of(variant("CM291BS", "Black Silver", 12)),
                List.of("Black Silver"), List.of("CM291BS"), List.of());

        ProductGroupPreview preview = groups.preview("CM291BS", List.of("CM291RS"));

        assertThat(preview.applicable()).isFalse();
        assertThat(preview.conflicts()).singleElement().asString()
                .contains("p-2-rose").contains("CM291XX");
        assertThat(preview.absorbed()).singleElement().satisfies(a ->
                assertThat(a.notRequested()).containsExactly("CM291XX"));
    }

    /**
     * A parent whose options the sync does not model is synced hands-off: it would take the ids and
     * never the variants, while the products it absorbed are archived. Refuse before that happens.
     */
    @Test
    void refusesAParentTheSyncWouldNotAddVariantsTo() {
        keychainInTwoProducts();
        when(sync.findByHandle("p-1-leatherette-oval-keychain")).thenReturn(storeProduct("Material"));

        ProductGroupPreview preview = groups.preview("CM291BS", List.of("CM291RS"));

        assertThat(preview.applicable()).isFalse();
        assertThat(preview.conflicts()).singleElement().asString().contains("material");
    }

    @Test
    void refusesMoreVariantsThanTheSyncCanRead() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(PARENT, "p-1-plaque", "CD900"), store(ROSE, "p-2-plaque", "CD901")));
        List<Variant> many = java.util.stream.IntStream.rangeClosed(1, ProductGroupService.MAX_VARIANTS + 1)
                .mapToObj(i -> variant("CD900-" + i, "C" + i, 1)).toList();
        supplierWouldProduce(many, many.stream().map(Variant::color).toList(),
                many.stream().map(v -> "CD900").toList(), List.of());

        ProductGroupPreview preview = groups.preview("CD900", List.of("CD901"));

        assertThat(preview.applicable()).isFalse();
        assertThat(preview.conflicts()).singleElement().asString().contains("101 variants");
    }

    // ---------------------------------------------------------------- apply

    /**
     * Record, archive, and only then remove: every step before the last one adds, so a failure
     * anywhere leaves a store the same grouping can finish. The sync comes after, and is what brings
     * the grouped variants onto the parent.
     */
    @Test
    void appliesByRecordingThenArchivingThenRemovingTheIds() {
        keychainInTwoProducts();
        SyncResult synced = new SyncResult("CM291BS", PARENT, "p-1-leatherette-oval-keychain", true, 2, 1,
                List.of());
        when(sync.importProduct("CM291BS")).thenReturn(synced);

        ProductGroupService.Applied applied = groups.apply("CM291BS", List.of("CM291RS"));

        InOrder order = inOrder(gql, sync);
        order.verify(gql).execute(eq(ShopifyGraphQL.METAFIELDS_SET), any());
        order.verify(gql).execute(eq(ShopifyGraphQL.PRODUCT_ARCHIVE), eq(Map.of("id", ROSE)));
        order.verify(gql).execute(eq(ShopifyGraphQL.METAFIELDS_DELETE), any());
        order.verify(sync).invalidateImportedIndex();
        order.verify(sync).importProduct("CM291BS");

        // The parent lists both ids, its own first; the rose product says where its id went.
        assertThat(sent(ShopifyGraphQL.METAFIELDS_SET, "metafields")).containsExactly(
                Map.of("ownerId", PARENT, "namespace", "custom", "key", "ps_product_ids",
                        "type", "list.single_line_text_field", "value", "[\"CM291BS\",\"CM291RS\"]"),
                Map.of("ownerId", ROSE, "namespace", "trophy_sync", "key", "grouped_into",
                        "type", "single_line_text_field", "value", "p-1-leatherette-oval-keychain"),
                Map.of("ownerId", ROSE, "namespace", "trophy_sync", "key", "grouped_ids",
                        "type", "list.single_line_text_field", "value", "[\"CM291RS\"]"));
        // Both identity keys come off, so the store index stops seeing the rose product at all.
        assertThat(sent(ShopifyGraphQL.METAFIELDS_DELETE, "metafields")).containsExactly(
                Map.of("ownerId", ROSE, "namespace", "custom", "key", "ps_product_id"),
                Map.of("ownerId", ROSE, "namespace", "custom", "key", "ps_product_ids"));

        assertThat(applied.archived()).containsExactly("p-2-rose-leatherette-oval-keychain");
        assertThat(applied.supplierIds()).containsExactly("CM291BS", "CM291RS");
        assertThat(applied.sync()).isEqualTo(synced);
        assertThat(applied.syncError()).isNull();
    }

    /** Applying cannot be talked past the preview: the same refusal, and not one write. */
    @Test
    void refusesToApplyWhatThePreviewRefuses() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(PARENT, "p-1-keychain", "CM291BS"),
                store(ROSE, "p-2-rose", "CM291RS", "CM291XX")));
        supplierWouldProduce(List.of(variant("CM291BS", "Black Silver", 12)),
                List.of("Black Silver"), List.of("CM291BS"), List.of());

        assertThatThrownBy(() -> groups.apply("CM291BS", List.of("CM291RS")))
                .isInstanceOf(GroupConflictException.class)
                .hasMessageContaining("CM291XX");

        verify(gql, never()).execute(anyString(), any());
        verify(sync, never()).importProduct(anyString());
    }

    /**
     * An archive refused half-way must not be followed by removing the ids: the rose product would be
     * live, unsynced, and no longer findable by anything — including a second attempt.
     */
    @Test
    void stopsBeforeRemovingAnythingWhenAWriteFails() {
        keychainInTwoProducts();
        when(gql.execute(eq(ShopifyGraphQL.PRODUCT_ARCHIVE), any())).thenReturn(json(
                "{\"productUpdate\":{\"userErrors\":[{\"field\":[\"id\"],\"message\":\"Throttled\"}]}}"));

        assertThatThrownBy(() -> groups.apply("CM291BS", List.of("CM291RS")))
                .isInstanceOf(ShopifySyncException.class)
                .hasMessageContaining("archiving p-2-rose-leatherette-oval-keychain")
                .hasMessageContaining("applying it again finishes it");

        verify(gql, never()).execute(eq(ShopifyGraphQL.METAFIELDS_DELETE), any());
        verify(sync, never()).importProduct(anyString());
    }

    /**
     * After a stop half-way both products list the rose id. Repeating the grouping must still find
     * the old product — the store index would only ever return the first — or it would never be
     * archived nor let go of the id.
     */
    @Test
    void aRepeatFindsTheProductAnEarlierAttemptLeftBehind() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(PARENT, "p-1-keychain", "CM291BS", "CM291RS"),
                store(ROSE, "p-2-rose", "CM291RS")));
        supplierWouldProduce(List.of(variant("CM291BS", "Black Silver", 12), variant("CM291RS", "Rose", 0)),
                List.of("Black Silver", "Rose"), List.of("CM291BS", "CM291RS"), List.of());

        ProductGroupService.Applied applied = groups.apply("CM291BS", List.of("CM291RS"));

        assertThat(applied.archived()).containsExactly("p-2-rose");
        verify(gql).execute(eq(ShopifyGraphQL.PRODUCT_ARCHIVE), eq(Map.of("id", ROSE)));
    }

    /**
     * The grouping is written before the sync runs, so a failed sync is reported next to it rather
     * than thrown: an error alone would read as "nothing happened".
     */
    @Test
    void aFailedSyncIsReportedAndTheGroupingStands() {
        keychainInTwoProducts();
        when(sync.importProduct("CM291BS")).thenThrow(new ShopifySyncException("productVariantsBulkCreate userErrors"));

        ProductGroupService.Applied applied = groups.apply("CM291BS", List.of("CM291RS"));

        assertThat(applied.sync()).isNull();
        assertThat(applied.syncError()).contains("productVariantsBulkCreate");
        assertThat(applied.archived()).containsExactly("p-2-rose-leatherette-oval-keychain");
        verify(gql).execute(eq(ShopifyGraphQL.METAFIELDS_DELETE), any());
    }

    @Test
    void namesAtLeastOneMember() {
        assertThatThrownBy(() -> groups.apply("CM291BS", List.of(" ", "cm291bs")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
