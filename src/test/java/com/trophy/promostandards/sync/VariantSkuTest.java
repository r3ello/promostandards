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
                new BigDecimal("2.00"), 1, List.of());
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
     * A product covering two families shares only "CM74", which would leave tails like "7BK" — a
     * number, not a suffix anyone can read. The whole part id is used instead: longer, but nobody has
     * to guess.
     */
    @Test
    void fallsBackToTheWholePartWhenTheTailIsNotOne() {
        Map<String, String> skus = skus("PS9001", "CM747BK", "CM747BL", "CM746RD");

        assertThat(skuOf(skus, "CM747BK")).isEqualTo("PS9001-CM747BK");
        assertThat(skuOf(skus, "CM746RD")).isEqualTo("PS9001-CM746RD");
    }

    /** Two parts that differ only past the sixth character get their full ids, never a duplicate SKU. */
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
