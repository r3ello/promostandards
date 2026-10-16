package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.CatalogStatusClassifier.StoreProduct;
import com.trophy.promostandards.sync.CatalogStatusClassifier.Supplier;
import com.trophy.promostandards.sync.model.CatalogStatusView.Category;
import com.trophy.promostandards.sync.model.CatalogStatusView.Entry;
import com.trophy.promostandards.sync.model.CatalogStatusView.SupplierEntry;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each rule of the catalog status, on the real cases it was written for (2026-10 store pass).
 */
class CatalogStatusClassifierTest {

    private final List<String> sellable = new java.util.ArrayList<>();
    private final Map<String, List<String>> productParts = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, List<String>> pricedParts = new HashMap<>();
    private final Map<String, List<String>> inventoryParts = new HashMap<>();

    /** A sellable PaceSetter product with product data and a price for each of its parts. */
    private void product(String id, String name, String... parts) {
        sellable.add(id);
        productParts.put(id, List.of(parts));
        pricedParts.put(id, List.of(parts));
        names.put(id, name);
    }

    private static StoreProduct store(String handle, String status, String lastSync, String... ids) {
        return new StoreProduct("gid://shopify/Product/" + Math.abs(handle.hashCode()), handle, handle, status,
                List.of("promostandards"), List.of(ids), lastSync, null, null);
    }

    private CatalogStatusClassifier.Result classify(StoreProduct... products) {
        return CatalogStatusClassifier.classify(List.of(products),
                new Supplier(sellable, productParts, names, pricedParts, inventoryParts));
    }

    private static Entry of(CatalogStatusClassifier.Result r, String handle) {
        return r.products().stream().filter(e -> e.handle().equals(handle)).findFirst().orElseThrow();
    }

    @Test
    void aSyncedProductStaysSyncedAndNamesTheCodesPaceSetterDropped() {
        product("CM717*", "Pilsner", "CM717BK", "CM717BL");
        var r = classify(store("p-1-pilsner", "ACTIVE", "2026-10-01T00:00:00Z", "CM717*", "CM717OLD"));

        Entry e = of(r, "p-1-pilsner");
        assertThat(e.category()).isEqualTo(Category.SYNCED);
        assertThat(e.reason()).isEqualTo("Synced; CM717OLD (PaceSetter no longer has it)");
        assertThat(e.syncable()).isFalse();
    }

    /**
     * Freestanding Lucite Block lists C0813A (a product) and CD549B, a size of CD549 — which PaceSetter
     * sells without product data. That code is left out of a sync, but it is not gone.
     */
    @Test
    void codesASyncLeavesOutAreToldApartByWhy() {
        product("C0813A", "Freestanding Lucite Block", "C0813A");
        sellable.add("CD549");
        pricedParts.put("CD549", List.of("CD549B"));
        var r = classify(store("p-5753-block", "ACTIVE", null, "C0813A", "CD549B", "OLD9"));

        assertThat(of(r, "p-5753-block").reason()).isEqualTo("PaceSetter has everything; never synced. "
                + "Not synced with it: CD549B (variants of CD549); OLD9 (PaceSetter no longer has it)");
    }

    /** CM751 was synced, then PaceSetter's Product Data stopped answering for it (2026-10-09). */
    @Test
    void aSyncedProductPaceSetterStoppedDescribingIsFlagged() {
        sellable.add("CM751");                     // still sellable, no product data, no price
        var r = classify(store("p-2-cheese-set", "ACTIVE", "2026-09-20T00:00:00Z", "CM751"));

        Entry e = of(r, "p-2-cheese-set");
        assertThat(e.category()).isEqualTo(Category.SYNCED_NO_DATA);
        assertThat(e.reason()).contains("has no product data for CM751");
    }

    @Test
    void aNeverSyncedProductWithEverythingIsReadyToSync() {
        product("GM833A", "Crystal Column, Baseball", "GM833A");
        var r = classify(store("p-3-column", "ACTIVE", null, "GM833A", "GM833F"));

        Entry e = of(r, "p-3-column");
        assertThat(e.category()).isEqualTo(Category.READY);
        assertThat(e.syncable()).isTrue();
        assertThat(e.reason()).contains("GM833F");
    }

    /**
     * Polar Camel Stemless lists CM712; "Polar Camel Medium" sells CM712's colours one by one. A sync
     * of the first would duplicate every colour of the second, so it is not offered — and the second
     * is a merge.
     */
    @Test
    void aSyncThatWouldDuplicateAnotherProductsVariantsIsNotOffered() {
        product("CM712", "Polar Camel 12 oz. Stemless", "CM712BL", "CM712GR");
        var r = classify(store("p-6356-stemless", "ACTIVE", null, "CM712"),
                store("p-8850-medium", "ACTIVE", null, "CM712BL", "CM712GR"));

        Entry stemless = of(r, "p-6356-stemless");
        assertThat(stemless.category()).isEqualTo(Category.READY);
        assertThat(stemless.syncable()).isFalse();
        assertThat(stemless.syncBlockedReason()).contains("p-8850-medium (CM712BL)").contains("merge first");

        Entry medium = of(r, "p-8850-medium");
        assertThat(medium.category()).isEqualTo(Category.NEEDS_FIX);
        assertThat(medium.reason()).contains("variants of CM712").contains("split across 2 store products");
    }

    /**
     * C609LGO's Inventory answer also lists C609LBL, which no product data or price table declares —
     * yet a sync would bring it in, and "Blue Spectrum … Large" sells it. Only the Inventory rows show it.
     */
    @Test
    void aVariantOnlyTheInventoryAnswerListsStillBlocksTheSync() {
        product("C609LGO", "Small Chisel Carve Tower", "C609LGO");
        inventoryParts.put("C609LGO", List.of("C609LGO", "C609LBL"));
        var r = classify(store("p-5898-gold", "ACTIVE", null, "C609LGO"),
                store("p-8720-blue", "ACTIVE", null, "C609LBL"));

        assertThat(of(r, "p-5898-gold").syncable()).isFalse();
        assertThat(of(r, "p-5898-gold").syncBlockedReason()).contains("p-8720-blue (C609LBL)");
        assertThat(of(r, "p-8720-blue").category()).isEqualTo(Category.NEEDS_FIX);
    }

    /** Only one store product lists the colour: repointing it to the product code is enough. */
    @Test
    void aLonePartIsAFixNotAMerge() {
        product("CD1222", "Perpetual Plaque", "CD1222", "CD1222PLT");
        var r = classify(store("p-7917-plate", "ACTIVE", null, "CD1222PLT"));

        Entry e = of(r, "p-7917-plate");
        assertThat(e.category()).isEqualTo(Category.NEEDS_FIX);
        assertThat(e.reason()).contains("variants of CD1222; list that code instead");
    }

    /** p-6356 listed CM713, but PaceSetter sells it as the family CM713*. */
    @Test
    void aCodeUnderAWildcardFamilyIsAFix() {
        product("CM713*", "Polar Camel 16 oz. Stemless", "CM713BK");
        var r = classify(store("p-1-tumbler", "ACTIVE", null, "CM713"));

        Entry e = of(r, "p-1-tumbler");
        assertThat(e.category()).isEqualTo(Category.NEEDS_FIX);
        assertThat(e.reason()).isEqualTo("CM713 is not a PaceSetter code; it sells CM713* — list that instead");
    }

    /** CM264A/B/C are parts of CM264, which PaceSetter sells and prices but has no product data for. */
    @Test
    void partsOfAProductPaceSetterCannotDescribeAreWaiting() {
        sellable.add("CM264");
        pricedParts.put("CM264", List.of("CM264A", "CM264B"));
        var r = classify(store("p-5266-cup", "ACTIVE", null, "CM264A", "CM264B"));

        Entry e = of(r, "p-5266-cup");
        assertThat(e.category()).isEqualTo(Category.WAITING);
        assertThat(e.reason()).contains("variants of CM264").contains("no product data (CM264)");
    }

    /** CD414* is described, variants and all, but has no price — a merge now would still not sync. */
    @Test
    void partsOfAnUnpricedProductAreWaitingNotAMerge() {
        sellable.add("CD414*");
        productParts.put("CD414*", List.of("CD414ABEF", "CD414BBEF"));
        var r = classify(store("p-8718-blue", "ACTIVE", null, "CD414ABEF"),
                store("p-7896-blue-b", "ACTIVE", null, "CD414BBEF"));

        assertThat(of(r, "p-8718-blue").category()).isEqualTo(Category.WAITING);
        assertThat(of(r, "p-8718-blue").reason()).contains("no price (CD414*)");
    }

    /**
     * "Beverage holder Large" lists the CM727* colours (priced) and the CM737* ones (no price): the
     * CM727* half can be merged now, so it is a fix — and the reason says what still waits.
     */
    @Test
    void aProductHalfOfWhichCanBeFixedIsAFixThatNamesTheOtherHalf() {
        product("CM727*", "Can Holder", "CM727BL", "CM727BK");
        sellable.add("CM737*");
        productParts.put("CM737*", List.of("CM737BL", "CM737BK"));
        var r = classify(store("p-8847-large", "ACTIVE", null, "CM727BL", "CM737BL"),
                store("p-8846-small", "ACTIVE", null, "CM727BK", "CM737BK"));

        Entry large = of(r, "p-8847-large");
        assertThat(large.category()).isEqualTo(Category.NEEDS_FIX);
        assertThat(large.reason()).startsWith("Its codes are variants of CM727*, split across 2 store products")
                .endsWith("Those of CM737* wait: PaceSetter has no price (CM737*)");
    }

    /** CM295CK falls under CM295*, but no answer of that family lists it: it is gone, not a fix. */
    @Test
    void aLongerCodeUnderAFamilyPrefixIsNotAFix() {
        product("CM295*", "Leatherette Luggage Tag", "CM295BK");
        var r = classify(store("p-5621-cork-tag", "DRAFT", null, "CM295CK"));

        Entry e = of(r, "p-5621-cork-tag");
        assertThat(e.category()).isEqualTo(Category.DISCONTINUED);
        assertThat(e.reason()).contains("CM295CK falls under CM295*, but no answer of that family lists it");
    }

    @Test
    void aSellableCodeWithoutAPriceIsWaiting() {
        sellable.add("CD1232");
        productParts.put("CD1232", List.of("CD1232"));
        var r = classify(store("p-8085-sculpted", "ACTIVE", null, "CD1232"));

        assertThat(of(r, "p-8085-sculpted").category()).isEqualTo(Category.WAITING);
        assertThat(of(r, "p-8085-sculpted").reason()).isEqualTo("PaceSetter has no price for CD1232");
    }

    @Test
    void codesPaceSetterHasNoTraceOfAreDiscontinuedAndTheMarkIsReported() {
        var tagged = new StoreProduct("gid://shopify/Product/42", "p-8903-water-drop", "Water Drop Award", "DRAFT",
                List.of("promostandards", "pacesetter-discontinued"), List.of("CM702-06"), null, null, null);
        var r = classify(tagged, store("p-1-active", "ACTIVE", null, "OLD1"));

        Entry e = of(r, "p-8903-water-drop");
        assertThat(e.category()).isEqualTo(Category.DISCONTINUED);
        assertThat(e.discontinuedTag()).isTrue();
        assertThat(e.adminId()).isEqualTo("42");
        assertThat(of(r, "p-1-active").discontinuedTag()).isFalse();
    }

    @Test
    void anArchivedProductSaysWhereItWent() {
        var merged = new StoreProduct("gid://shopify/Product/7", "p-6200-red", "Red", "ARCHIVED",
                List.of("promostandards"), List.of(), null, null, "p-5911-green");
        var r = classify(merged);

        assertThat(of(r, "p-6200-red").category()).isEqualTo(Category.ARCHIVED);
        assertThat(of(r, "p-6200-red").reason()).isEqualTo("Merged into p-5911-green");
    }

    @Test
    void paceSetterProductsTheStoreDoesNotCarryAreNewUnlessTheirVariantsAreAlreadyThere() {
        product("CM468", "Crystal Ornament", "CM468");
        product("CM710*", "Polar Camel 30 oz.", "CM710BL");
        sellable.add("CM999");                     // no product data: not importable
        var r = classify(store("p-6168-ringneck", "ACTIVE", null, "CM710BL"));

        assertThat(r.newAtSupplier()).extracting(SupplierEntry::productId).containsExactly("CM468");
        assertThat(r.newAtSupplier().get(0).name()).isEqualTo("Crystal Ornament");
        assertThat(r.related()).singleElement().satisfies(s -> {
            assertThat(s.productId()).isEqualTo("CM710*");
            assertThat(s.relatedHandles()).containsExactly("p-6168-ringneck");
        });
        assertThat(r.notImportable()).isEqualTo(1);
    }

    @Test
    void codesAreMatchedWhateverTheirCase() {
        product("GI603", "Tower", "GI603");
        var r = classify(store("p-1-tower", "ACTIVE", "2026-10-01T00:00:00Z", "gi603 "));

        assertThat(of(r, "p-1-tower").category()).isEqualTo(Category.SYNCED);
        assertThat(r.newAtSupplier()).isEmpty();
    }

    @Test
    void entriesComeInConsoleOrder() {
        product("A1", "A", "A1");
        var r = classify(store("p-z", "ACTIVE", null, "GONE"), store("p-a", "ACTIVE", "2026-10-01T00:00:00Z", "A1"));

        assertThat(r.products()).extracting(Entry::category)
                .containsExactly(Category.SYNCED, Category.DISCONTINUED);
    }
}
