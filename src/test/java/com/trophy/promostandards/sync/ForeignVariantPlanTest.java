package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.ForeignVariantPlan.Action;
import com.trophy.promostandards.sync.ForeignVariantPlan.StoreVariant;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What happens to each supplier variant of a store product this app did not create. */
class ForeignVariantPlanTest {

    private static Variant variant(String partId, String color, String size) {
        return new Variant(partId, color, size, partId + "-" + size, null, null, null, List.of());
    }

    private static ForeignVariantPlan plan(List<Variant> supplier, List<StoreVariant> store) {
        return ForeignVariantPlan.of(supplier, VariantOptions.colorLabels(supplier), true, store);
    }

    /** The migrated shape: one legacy variant, matching nothing, next to the supplier's variants. */
    @Test
    void adoptsTheLoneLegacyVariantAndCreatesTheRest() {
        List<Variant> supplier = List.of(
                variant("CM297BL", "Dark Brown", "12 X 9.5"),
                variant("CM297RW", "Rawhide", "12 X 9.5"));
        StoreVariant legacy = new StoreVariant("gid://ProductVariant/93", "PS11592", null,
                "Default Title", null, "gid://InventoryItem/193", false, null);

        ForeignVariantPlan plan = plan(supplier, List.of(legacy));

        assertThat(plan.entries()).extracting(ForeignVariantPlan.Entry::action)
                .containsExactly(Action.ADOPT, Action.CREATE);
        assertThat(plan.adopted().target()).isEqualTo(legacy);
        assertThat(plan.adopted().colorLabel()).isEqualTo("Dark Brown");
        assertThat(plan.orphans()).isEmpty();
    }

    /** The second sync: the variants carry their supplier id, so nothing depends on the SKU. */
    @Test
    void matchesByTheVariantIdentityMetafieldThenByOptionsThenBySku() {
        List<Variant> supplier = List.of(
                variant("CM297BL", "Dark Brown", "12 X 9.5"),
                variant("CM297RW", "Rawhide", "12 X 9.5"),
                variant("CM297TL", "Teal", "12 X 9.5"));
        List<StoreVariant> store = List.of(
                // renamed in the store, but stamped with its supplier id
                new StoreVariant("gid://ProductVariant/1", "CHANGED-SKU", "CM297BL", "Chocolate",
                        "12 X 9.5", "gid://InventoryItem/1", true, 0),
                // no identity metafield and a stale SKU, but the option values still line up
                new StoreVariant("gid://ProductVariant/2", "OLD-SKU", null, "Rawhide", "12 X 9.5",
                        "gid://InventoryItem/2", true, 0),
                new StoreVariant("gid://ProductVariant/3", "CM297TL-12 X 9.5", null, null, null,
                        "gid://InventoryItem/3", true, 0));

        ForeignVariantPlan plan = plan(supplier, store);

        assertThat(plan.entries()).extracting(e -> e.target().id()).containsExactly(
                "gid://ProductVariant/1", "gid://ProductVariant/2", "gid://ProductVariant/3");
        assertThat(plan.toCreate()).isEmpty();
        assertThat(plan.toUpdate()).hasSize(3);
    }

    /** Several unmatched store variants are ambiguous: none is adopted, they are reported instead. */
    @Test
    void leavesSeveralUnmatchedStoreVariantsAsOrphans() {
        List<Variant> supplier = List.of(variant("CM297BL", "Dark Brown", "12 X 9.5"));
        List<StoreVariant> store = List.of(
                new StoreVariant("gid://ProductVariant/1", "LEGACY-A", null, "Oak", "Large",
                        "gid://InventoryItem/1", true, 0),
                new StoreVariant("gid://ProductVariant/2", "LEGACY-B", null, "Walnut", "Large",
                        "gid://InventoryItem/2", true, 0));

        ForeignVariantPlan plan = plan(supplier, store);

        assertThat(plan.entries()).singleElement()
                .satisfies(e -> assertThat(e.action()).isEqualTo(Action.CREATE));
        assertThat(plan.orphans()).hasSize(2);
    }

    /** One store variant may only stand for one supplier variant, however many ways it matches. */
    @Test
    void neverClaimsTheSameStoreVariantTwice() {
        List<Variant> supplier = List.of(
                variant("CM297BL", "Dark Brown", "12 X 9.5"),
                variant("CM297LB", "Dark Brown", "12 X 9.5"));
        List<StoreVariant> store = List.of(
                new StoreVariant("gid://ProductVariant/1", "CM297BL-12 X 9.5", null,
                        "Dark Brown (BL)", "12 X 9.5", "gid://InventoryItem/1", true, 0));

        ForeignVariantPlan plan = plan(supplier, store);

        assertThat(plan.entries().get(0).action()).isEqualTo(Action.UPDATE);
        assertThat(plan.entries().get(1).action()).isEqualTo(Action.CREATE);
        assertThat(plan.entries().get(1).colorLabel()).isEqualTo("Dark Brown (LB)");
    }

    @Test
    void listsEverySupplierIdItCovers() {
        List<Variant> supplier = List.of(
                variant("CM297BL", "Dark Brown", "12 X 9.5"),
                variant("CM297BB", "Bamboo Black", "12 X 9.5"));

        assertThat(plan(supplier, List.of()).supplierIds()).containsExactly("CM297BL", "CM297BB");
    }
}
