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

    /**
     * CD902Y*, as PaceSetter serves it on 2026-10-08: fourteen parts, one per year, of which only
     * the ones it reports stock for carry a colour and a size. The year is what tells them apart.
     */
    @Test
    void namesAYearsFamilyByTheYearAlone() {
        List<Variant> variants = List.of(
                variant("CD902Y1", null, null),
                variant("CD902Y10", "Black Frost", "9 X 7 X 0.875"),
                variant("CD902Y15", "Black Frost", "9 X 7 X 0.875"),
                variant("CD902Y2", null, null),
                variant("CD902Y5", "Black Frost", "9 X 7 X 0.875"),
                variant("CD902Y50", null, null));

        // The shared stem is CD902Y1 for the first three; it is backed off to the Y.
        assertThat(VariantOptions.yearLabels(variants)).containsExactly("1", "10", "15", "2", "5", "50");
        assertThat(VariantOptions.yearLabels(List.of(variant("CD904Y10", null, null),
                variant("CD904Y15", null, null)))).containsExactly("10", "15");
    }

    @Test
    void anythingElseIsNotAYearsFamily() {
        // A stem that does not end in Y: a measurement or a colour code.
        assertThat(VariantOptions.yearLabels(List.of(variant("C0610", null, null),
                variant("C0611", null, null)))).isNull();
        // Different products that happen to carry a year (CD1235AY5 / CD1236AY5B).
        assertThat(VariantOptions.yearLabels(List.of(variant("CD1235AY5", null, null),
                variant("CD1236AY5B", null, null)))).isNull();
        // A year sold in two sizes: the year alone would name two variants the same.
        assertThat(VariantOptions.yearLabels(List.of(variant("CD902Y5", null, "S"),
                variant("CD902Y5", null, "M")))).isNull();
        // A part whose tail is not a number, and a lone part.
        assertThat(VariantOptions.yearLabels(List.of(variant("CD902Y5", null, null),
                variant("CD902Y5B", null, null)))).isNull();
        assertThat(VariantOptions.yearLabels(List.of(variant("CD902Y5", null, null)))).isNull();
    }

    private static Variant uncoloured(String partId, String label) {
        return new Variant(partId, null, null, partId, null, null, null, List.of(), null, null, label);
    }
}
