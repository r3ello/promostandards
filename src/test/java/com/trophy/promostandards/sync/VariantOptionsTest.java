package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Colour labels, on the data that forced them: PaceSetter's CM297 portfolio family gives four parts
 * three colour names ({@code BL}/{@code LB} are both "Dark Brown", {@code CK}/{@code PK} both
 * "Grey"), all in one size. Shopify rejects two variants with the same option combination, so a
 * shared colour has to carry what tells the parts apart.
 */
class VariantOptionsTest {

    private static Variant variant(String partId, String color, String size) {
        return new Variant(partId, color, size, partId, null, null, null, List.of(), null, null);
    }

    @Test
    void suffixesOnlyTheColoursTwoPartsShare() {
        List<Variant> variants = List.of(
                variant("CM297BL", "Dark Brown", "12 X 9.5"),
                variant("CM297LB", "Dark Brown", "12 X 9.5"),
                variant("CM297RW", "Rawhide", "12 X 9.5"),
                variant("CM297CK", "Grey", "12 X 9.5"),
                variant("CM297PK", "Grey", "12 X 9.5"));

        assertThat(VariantOptions.colorLabels(variants)).containsExactly(
                "Dark Brown (BL)", "Dark Brown (LB)", "Rawhide", "Grey (CK)", "Grey (PK)");
    }

    @Test
    void keepsTheWholePartIdWhenOneIsAPrefixOfTheOther() {
        List<Variant> variants = List.of(
                variant("EP2", "Brown Gold", "10.25 X 13"),
                variant("EP2PK", "Brown Gold", "10.25 X 13"));

        // Stripping the common prefix would leave the first suffix empty, so neither is stripped.
        assertThat(VariantOptions.colorLabels(variants))
                .containsExactly("Brown Gold (EP2)", "Brown Gold (EP2PK)");
    }

    @Test
    void leavesDistinctCombinationsAlone() {
        List<Variant> variants = List.of(
                variant("SAMPLE-RED", "Red", "S"),
                variant("SAMPLE-RED", "Red", "M"),
                variant("SAMPLE-BLU", "Blue", "S"));

        assertThat(VariantOptions.colorLabels(variants)).containsExactly("Red", "Red", "Blue");
    }

    @Test
    void fallsBackToPlaceholdersWhenTheSupplierGivesNoColourOrSize() {
        List<Variant> variants = List.of(variant("GI307", null, null));

        assertThat(VariantOptions.colorLabels(variants)).containsExactly("Default");
        assertThat(VariantOptions.size(null)).isEqualTo("One Size");
        assertThat(VariantOptions.hasSize(variants)).isFalse();
    }

    /**
     * A variant PaceSetter gives no colour is named by its description when that says something, and
     * by its part code when it does not — "BS", never "Default (BS)". Two parts whose descriptions
     * name the same thing still get told apart by their codes.
     */
    @Test
    void namesUncolouredVariantsByLabelThenByCode() {
        assertThat(VariantOptions.colorLabels(List.of(
                uncoloured("CM330BS", null), uncoloured("CM330DB", null))))
                .containsExactly("BS", "DB");
        assertThat(VariantOptions.colorLabels(List.of(
                uncoloured("C021ABEF", "Ebony"), uncoloured("C021AGEF", "Ebony"),
                uncoloured("C021ABWF", "Walnut"))))
                .containsExactly("Ebony (BEF)", "Ebony (GEF)", "Walnut");
    }

    /**
     * One unnamed part among named ones (CM731: Black, Black/Rose Gold, Blue) takes its code, not
     * "Default" — but a product with no names at all (one part in several sizes) keeps "Default",
     * as it always has.
     */
    @Test
    void anUnnamedVariantAmongNamedOnesShowsItsCode() {
        assertThat(VariantOptions.colorLabels(List.of(
                new Variant("CM731BK", "Black", null, "CM731BK", null, null, null, List.of(), null, null),
                uncoloured("CM731BKRG", null),
                new Variant("CM731CR", "Coral", null, "CM731CR", null, null, null, List.of(), null, null))))
                .containsExactly("Black", "BKRG", "Coral");
        assertThat(VariantOptions.colorLabels(List.of(
                new Variant("SAMPLE-001", null, "S", "SAMPLE-001-S", null, null, null, List.of(), null, null),
                new Variant("SAMPLE-001", null, "M", "SAMPLE-001-M", null, null, null, List.of(), null, null))))
                .containsExactly("Default", "Default");
    }

    private static Variant uncoloured(String partId, String label) {
        return new Variant(partId, null, null, partId, null, null, null, List.of(), null, null, label);
    }
}
