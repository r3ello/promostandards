package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The store's own variant numbering: the legacy catalogue's SKU plus what tells the supplier's parts
 * apart. The client's worked example is {@code PS1298} over the CM2541 family.
 */
class VariantSkuTest {

    /** A supplier variant reduced to what numbering cares about: its part id and size. */
    private static Variant variant(String partId, String size) {
        return new Variant(partId, "Colour", size, partId + "-" + size, new BigDecimal("1.00"),
                new BigDecimal("2.00"), 1, List.of(), null, null);
    }

    private static Map<String, String> skus(String legacy, String... partIds) {
        return VariantSku.byVariant(legacy,
                java.util.Arrays.stream(partIds).map(p -> variant(p, "One Size")).toList());
    }

    private static String skuOf(Map<String, String> skus, String partId) {
        return skus.get(VariantSku.key(partId, "One Size"));
    }

    @Test
    void numbersTheFamilyFromTheLegacySku() {
        Map<String, String> skus = skus("PS1298",
                "CM2541LB", "CM2541LG", "CM2541RB", "CM2541YO", "CM2541BG");

        assertThat(skus.values()).containsExactly("PS1298-LB", "PS1298-LG", "PS1298-RB",
                "PS1298-YO", "PS1298-BG");
        assertThat(skuOf(skus, "CM2541RB")).isEqualTo("PS1298-RB");
    }

    /** One part is the whole product: there is nothing to distinguish, so the legacy SKU stands. */
    @Test
    void leavesASingleVariantOnTheLegacySkuItself() {
        assertThat(skus("PS11430", "GI307").values()).containsExactly("PS11430");
    }

    /**
     * The client's second worked example: several families under one product keep the digit that
     * tells them apart. Common prefix CM254, so the tails are what is left.
     */
    @Test
    void keepsTheDigitThatSeparatesFamilies() {
        Map<String, String> skus = skus("PS1298",
                "CM2541LB", "CM2541LG", "CM2542RB", "CM2542YO", "CM2543BG");

        assertThat(skus.values()).containsExactly("PS1298-1LB", "PS1298-1LG", "PS1298-2RB",
                "PS1298-2YO", "PS1298-3BG");
    }

    /** The real two-family product in the store: CM746 and CM747 share CM74, and that is the cut. */
    @Test
    void namesTwoFamiliesByWhatDiffers() {
        Map<String, String> skus = skus("PS9001", "CM747BK", "CM747BL", "CM746RD");

        assertThat(skuOf(skus, "CM747BK")).isEqualTo("PS9001-7BK");
        assertThat(skuOf(skus, "CM746RD")).isEqualTo("PS9001-6RD");
    }

    /** An id that is itself the prefix of the others has no difference to be named by. */
    @Test
    void fallsBackToTheWholePartWhenNothingIsLeft() {
        Map<String, String> skus = skus("PS500", "CM254", "CM2541LB");

        assertThat(skuOf(skus, "CM254")).isEqualTo("PS500-CM254");
        assertThat(skuOf(skus, "CM2541LB")).isEqualTo("PS500-1LB");
    }

    /** Whatever the ids look like, two variants never end up sharing a SKU. */
    @Test
    void neverIssuesTheSameSkuTwice() {
        assertThat(skus("PS7", "CD916ABLONGTAIL", "CD916ABLONGTAIX").values()).doesNotHaveDuplicates();
    }

    /** One part sold in two sizes is two variants: the size joins the tail so the SKUs stay distinct. */
    @Test
    void keepsSizesApartUnderOnePartId() {
        Map<String, String> skus = VariantSku.byVariant("PS500",
                List.of(variant("SAMPLE-RED", "S"), variant("SAMPLE-RED", "M"),
                        variant("SAMPLE-BLU", "S")));

        assertThat(skus.values()).doesNotHaveDuplicates();
        assertThat(skus.get(VariantSku.key("SAMPLE-RED", "S"))).isNotEqualTo(
                skus.get(VariantSku.key("SAMPLE-RED", "M")));
    }

    /** A product the migration never numbered keeps the supplier-derived SKUs (empty map = fallback). */
    @Test
    void staysOutOfTheWayWithoutALegacySku() {
        assertThat(skus(null, "CM2541LB")).isEmpty();
        assertThat(skus("  ", "CM2541LB")).isEmpty();
    }
}
