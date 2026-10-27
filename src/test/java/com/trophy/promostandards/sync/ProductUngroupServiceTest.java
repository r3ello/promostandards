package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.sync.ShopifySyncService.ImportedProduct;
import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.model.ProductUngroupPreview;
import com.trophy.promostandards.sync.model.ProductUngroupPreview.Action;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

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
 * Selling the ids of a grouped store product separately again, as was done by hand for the CD900*
 * state cutouts on 2026-10-23: the grouped product loses the ids and their variants, the products
 * the grouping archived come back with their ids, and every one of them is kept to its own parts —
 * without that mark, PaceSetter's family answer puts every state back into every product.
 */
class ProductUngroupServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ALASKA = "gid://shopify/Product/1";
    private static final String ALABAMA = "gid://shopify/Product/2";
    private static final String ILLINOIS = "gid://shopify/Product/3";
    private static final String ALASKA_HANDLE = "p-5805-alaska-state-silhouette-awards";

    private final ShopifySyncService sync = mock(ShopifySyncService.class);
    private final ShopifyGraphQLClient gql = mock(ShopifyGraphQLClient.class);
    private final ProductUngroupService ungroups = new ProductUngroupService(sync, gql, MAPPER);

    @BeforeEach
    void storeAcceptsEveryWrite() {
        when(gql.execute(anyString(), any())).thenReturn(json("""
                {"metafieldsSet":{"userErrors":[]},"productUpdate":{"userErrors":[]},
                 "metafieldsDelete":{"userErrors":[]},"productVariantsBulkDelete":{"userErrors":[]}}"""));
        when(sync.appHandle(anyString())).thenAnswer(i -> "ps-pacesetter-" + i.getArgument(0));
        when(sync.findByHandle(ALASKA_HANDLE)).thenReturn(grouped("CD900AK", "CD900AL", "CD900IL"));
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The grouped product as the store holds it: one State variant per id it was synced with. */
    private static JsonNode grouped(String... psIds) {
        StringBuilder variants = new StringBuilder();
        for (int i = 0; i < psIds.length; i++) {
            variants.append(i == 0 ? "" : ",").append("{\"id\":\"gid://shopify/ProductVariant/").append(i + 1)
                    .append("\",\"sku\":\"PS6487-").append(psIds[i].substring(5)).append("\",")
                    .append("\"psId\":{\"value\":\"").append(psIds[i]).append("\"}}");
        }
        return json("{\"id\":\"" + ALASKA + "\",\"handle\":\"" + ALASKA_HANDLE + "\","
                + "\"variants\":{\"nodes\":[" + variants + "]}}");
    }

    private static ImportedProduct store(String gid, String handle, String... ids) {
        return new ImportedProduct(gid, handle, ids[0], List.of(ids), "migration", null);
    }

    /** What the store lists with a grouping note: {@code [gid, handle, status, groupedInto, groupedIds, psId]}. */
    private void storeNotes(String[]... rows) {
        StringBuilder nodes = new StringBuilder();
        for (String[] r : rows) {
            nodes.append(nodes.isEmpty() ? "" : ",")
                    .append("{\"id\":\"").append(r[0]).append("\",\"handle\":\"").append(r[1])
                    .append("\",\"status\":\"").append(r[2]).append("\",")
                    .append("\"groupedInto\":").append(value(r[3])).append(",")
                    .append("\"groupedIds\":").append(value(r[4])).append(",")
                    .append("\"psId\":").append(value(r[5])).append(",\"psIds\":null}");
        }
        when(gql.execute(eq(ShopifyGraphQL.GROUPED_PRODUCTS), any())).thenReturn(json(
                "{\"products\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[" + nodes + "]}}"));
    }

    private static String value(String v) {
        return v == null ? "null" : "{\"value\":" + MAPPER.valueToTree(v) + "}";
    }

    /** Alaska grouped Alabama and Illinois, whose products sit archived with the note. */
    private void statesGrouped() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(ALASKA, ALASKA_HANDLE, "CD900AK", "CD900AL", "CD900IL")));
        storeNotes(
                new String[]{ALABAMA, "p-5806-alabama", "ARCHIVED", ALASKA_HANDLE, "[\"CD900AL\"]", null},
                new String[]{ILLINOIS, "p-5820-illinois", "ARCHIVED", ALASKA_HANDLE, "CD900IL", null},
                new String[]{"gid://shopify/Product/9", "p-9-elsewhere", "ARCHIVED", "p-other", "[\"X1\"]", null});
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sentMetafields() {
        ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
        verify(gql).execute(eq(ShopifyGraphQL.METAFIELDS_SET), vars.capture());
        return (List<Map<String, Object>>) vars.getValue().get("metafields");
    }

    private static boolean has(List<Map<String, Object>> metafields, String owner, String key, String value) {
        return metafields.stream().anyMatch(m -> owner.equals(m.get("ownerId")) && key.equals(m.get("key"))
                && value.equals(m.get("value")));
    }

    // ---------------------------------------------------------------- preview

    @Test
    void showsWhatComesBackAndWhatLeavesTheGroupedProduct() {
        statesGrouped();

        ProductUngroupPreview preview = ungroups.preview("CD900AL", List.of());

        assertThat(preview.applicable()).isTrue();
        // Asked by any of its ids, the grouped product keeps its own.
        assertThat(preview.productId()).isEqualTo("CD900AK");
        assertThat(preview.keptIds()).containsExactly("CD900AK");
        assertThat(preview.split()).extracting(ProductUngroupPreview.Split::handle)
                .containsExactly("p-5806-alabama", "p-5820-illinois");
        assertThat(preview.split()).allSatisfy(s -> assertThat(s.action()).isEqualTo(Action.RESTORE));
        // A Matrixify sheet wrote the note as plain text; it is read all the same.
        assertThat(preview.split().get(1).supplierIds()).containsExactly("CD900IL");
        assertThat(preview.removedVariants()).containsExactly("PS6487-AL", "PS6487-IL");
        verify(gql, never()).execute(eq(ShopifyGraphQL.METAFIELDS_SET), any());
    }

    @Test
    void theGroupedProductKeepsItsOwnId() {
        statesGrouped();

        ProductUngroupPreview preview = ungroups.preview("CD900AK", List.of("CD900AK", "CD900AL"));

        assertThat(preview.applicable()).isFalse();
        assertThat(preview.conflicts()).anyMatch(c -> c.contains("CD900AK is " + ALASKA_HANDLE + "'s own id"));
    }

    /** An archived product that had two ids comes back with both, or it would come back short. */
    @Test
    void aRestoredProductTakesBackEveryIdItHad() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(ALASKA, ALASKA_HANDLE, "CD900AK", "CD900AL", "CD900IL")));
        storeNotes(new String[]{ALABAMA, "p-5806-alabama", "ARCHIVED", ALASKA_HANDLE, "[\"CD900AL\",\"CD900IL\"]", null});

        ProductUngroupPreview preview = ungroups.preview("CD900AK", List.of("CD900AL"));

        assertThat(preview.applicable()).isFalse();
        assertThat(preview.conflicts()).anyMatch(c -> c.contains("p-5806-alabama had [CD900IL]"));
        assertThat(ungroups.preview("CD900AK", List.of("CD900AL", "CD900IL")).applicable()).isTrue();
    }

    /** The migration sold some families grouped from the start: nothing to bring back for those. */
    @Test
    void anIdNoArchivedProductRemembersNeedsCreationSwitchedOn() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(ALASKA, ALASKA_HANDLE, "CD900AK", "CD900AL", "CD900IL")));
        storeNotes();

        ProductUngroupPreview off = ungroups.preview("CD900AK", List.of("CD900IL"));
        assertThat(off.applicable()).isFalse();
        assertThat(off.conflicts()).anyMatch(c -> c.contains("creating products is off"));

        when(sync.canCreateProducts()).thenReturn(true);
        ProductUngroupPreview on = ungroups.preview("CD900AK", List.of("CD900IL"));
        assertThat(on.applicable()).isTrue();
        assertThat(on.split()).singleElement().satisfies(s -> {
            assertThat(s.action()).isEqualTo(Action.CREATE);
            assertThat(s.handle()).isNull();
        });
    }

    @Test
    void anIdAlreadySoldOnItsOwnIsNotTakenTwice() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(ALASKA, ALASKA_HANDLE, "CD900AK", "CD900AL"),
                store(ILLINOIS, "p-5820-illinois", "CD900IL")));
        storeNotes();

        ProductUngroupPreview preview = ungroups.preview("CD900AK", List.of("CD900IL"));

        assertThat(preview.conflicts()).anyMatch(c -> c.contains("CD900IL is already carried by p-5820-illinois"));
    }

    @Test
    void everyVariantGoingIsRefused() {
        statesGrouped();
        // Never synced since the grouping, except under the split ids' names.
        when(sync.findByHandle(ALASKA_HANDLE)).thenReturn(grouped("CD900AL", "CD900IL"));

        ProductUngroupPreview preview = ungroups.preview("CD900AK", List.of());

        assertThat(preview.conflicts()).anyMatch(c -> c.contains("keep at least the variants of CD900AK"));
    }

    // ---------------------------------------------------------------- apply

    @Test
    void writesInAnOrderThatNeverPutsAnIdOnTwoProducts() {
        statesGrouped();
        when(sync.importProduct("CD900AK")).thenReturn(
                new SyncResult("CD900AK", ALASKA, ALASKA_HANDLE, true, 1, 1, List.of()));

        ProductUngroupService.Applied applied = ungroups.apply("CD900AK", List.of(), "active", true);

        InOrder order = inOrder(gql, sync);
        order.verify(gql).execute(eq(ShopifyGraphQL.VARIANTS_BULK_DELETE), eq(Map.of("productId", ALASKA,
                "variantsIds", List.of("gid://shopify/ProductVariant/2", "gid://shopify/ProductVariant/3"))));
        order.verify(gql).execute(eq(ShopifyGraphQL.METAFIELDS_SET), any());
        order.verify(gql).execute(eq(ShopifyGraphQL.PRODUCT_STATUS), eq(Map.of("id", ALABAMA, "status", "ACTIVE")));
        order.verify(gql).execute(eq(ShopifyGraphQL.PRODUCT_STATUS), eq(Map.of("id", ILLINOIS, "status", "ACTIVE")));
        order.verify(gql).execute(eq(ShopifyGraphQL.METAFIELDS_DELETE), any());
        order.verify(sync).importProduct("CD900AK");

        List<Map<String, Object>> written = sentMetafields();
        assertThat(has(written, ALASKA, "ps_product_ids", "[\"CD900AK\"]")).isTrue();
        assertThat(has(written, ALASKA, "own_parts_only", "true")).isTrue();
        assertThat(has(written, ALABAMA, "ps_product_id", "CD900AL")).isTrue();
        assertThat(has(written, ALABAMA, "ps_product_ids", "[\"CD900AL\"]")).isTrue();
        assertThat(has(written, ILLINOIS, "own_parts_only", "true")).isTrue();
        assertThat(has(written, ILLINOIS, "keep_store_images", "true")).isTrue();
        // The grouped product's own photos are not this split's business.
        assertThat(has(written, ALASKA, "keep_store_images", "true")).isFalse();

        assertThat(applied.restored()).containsExactly("p-5806-alabama", "p-5820-illinois");
        assertThat(applied.toSync()).containsExactly("CD900AL", "CD900IL");
        assertThat(applied.sync()).isNotNull();
    }

    /** Stopped after the ids moved: Alabama is back and active, but still carries the note. */
    @Test
    void applyingAgainFinishesASplitThatStoppedPartWay() {
        when(sync.importedProductsOrEmpty()).thenReturn(List.of(
                store(ALASKA, ALASKA_HANDLE, "CD900AK"),
                store(ALABAMA, "p-5806-alabama", "CD900AL")));
        when(sync.findByHandle(ALASKA_HANDLE)).thenReturn(grouped("CD900AK"));
        storeNotes(new String[]{ALABAMA, "p-5806-alabama", "ACTIVE", ALASKA_HANDLE, "[\"CD900AL\"]", "CD900AL"});

        ProductUngroupService.Applied applied = ungroups.apply("CD900AK", List.of("CD900AL"), null, false);

        assertThat(applied.restored()).containsExactly("p-5806-alabama");
        verify(gql, never()).execute(eq(ShopifyGraphQL.VARIANTS_BULK_DELETE), any());
        verify(gql, never()).execute(eq(ShopifyGraphQL.PRODUCT_STATUS), any());
        verify(gql).execute(eq(ShopifyGraphQL.METAFIELDS_DELETE), any());
    }

    @Test
    void aRefusedSplitWritesNothing() {
        statesGrouped();

        assertThatThrownBy(() -> ungroups.apply("CD900AK", List.of("CD900AK"), "ACTIVE", false))
                .isInstanceOf(GroupConflictException.class)
                .hasMessageStartingWith("Split not applied");
        verify(gql, never()).execute(eq(ShopifyGraphQL.METAFIELDS_SET), any());
        verify(gql, never()).execute(eq(ShopifyGraphQL.VARIANTS_BULK_DELETE), any());
        verify(sync, never()).importProduct(anyString());
    }

    @Test
    void aFailedSyncOfTheGroupedProductIsReportedNotThrown() {
        statesGrouped();
        when(sync.importProduct("CD900AK")).thenThrow(new ShopifySyncException("PaceSetter timed out"));

        ProductUngroupService.Applied applied = ungroups.apply("CD900AK", List.of(), "DRAFT", false);

        assertThat(applied.sync()).isNull();
        assertThat(applied.syncError()).contains("PaceSetter timed out");
        verify(gql).execute(eq(ShopifyGraphQL.PRODUCT_STATUS), eq(Map.of("id", ALABAMA, "status", "DRAFT")));
    }

    @Test
    void onlyActiveOrDraft() {
        assertThatThrownBy(() -> ungroups.apply("CD900AK", List.of(), "ARCHIVED", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** What the split ids are synced with: a created one is kept to its own parts from its first sync. */
    @Test
    void theSplitIdsAreSyncedKeptToTheirOwnParts() {
        assertThat(ProductUngroupService.syncMetafields(false)).singleElement().satisfies(m -> {
            assertThat(m.namespace()).isEqualTo("trophy_sync");
            assertThat(m.key()).isEqualTo("own_parts_only");
            assertThat(m.type()).isEqualTo("boolean");
            assertThat(m.value()).isEqualTo("true");
        });
        assertThat(ProductUngroupService.syncMetafields(true))
                .extracting(SyncProperties.Metafield::key).containsExactly("own_parts_only", "keep_store_images");
    }
}
