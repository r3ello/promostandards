package com.trophy.promostandards.sync;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * Naming a part after the count in its description — the anniversary families.
 *
 * <p>The store's anniversary awards imported as "1", "10", "45": the part codes. The year that tells
 * them apart was in every description and still distinguished nothing, because the "*Please Specify"
 * note lists every year on most parts of the family. Counts also need each other: "10" beside
 * "Green 10" (CD1260) never says what makes the first one different, so where one part is named by a
 * count they all must be, or none is. Naming by words is untouched — see
 * {@link CatalogServicePlaceholderTest#namesPartsByWhatTheirDescriptionsDoNotShare()}.
 */
class CatalogServiceLabelsTest {

    private static Map<String, String> of(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void theYearNamesAnAnniversaryAwardOnceTheBuyerNoteIsOut() {
        // CD981Y*: the note on the first part lists 1, 3 and 5, so left in, every year is carried by
        // two parts of three and none of them distinguishes anything.
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "CD981Y1", "7'' x 4'' Anniversary Recognition 1 Year; Laser engraved;"
                        + " *Please Specify: 1, 3, 5",
                "CD981Y3", "7'' x 4'' Anniversary Recognition 3 Years; Laser engraved;",
                "CD981Y5", "7'' x 4'' Anniversary Recognition 5 Years; Laser engraved;"));

        assertThat(labels).containsExactly(
                entry("CD981Y1", "1 Year"), entry("CD981Y3", "3 Years"), entry("CD981Y5", "5 Years"));
    }

    @Test
    void aPartIsNotNamedAfterItsOwnCode() {
        // "Green (GR)" on a part whose id ends GR: the code says nothing the option value does not.
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "CD1260Y10GR", "Interlocking Bar; Green (GR)",
                "CD1260Y10BL", "Interlocking Bar; Blue (BL)"));

        assertThat(labels).containsExactly(
                entry("CD1260Y10GR", "Green"), entry("CD1260Y10BL", "Blue"));
    }

    @Test
    void aCountBesideAWordNamesNeither() {
        // CD1260 imported as "10BL" and "Green GR (10GR)". Naming it "10" and "Green 15" would be
        // distinct and still unreadable: the first says nothing about being the blue one.
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "CD1260Y10BL", "Two Level Bar 10 Years",
                "CD1260Y15GR", "Two Level Bar Green 15"));

        assertThat(labels).isEmpty();
    }

    @Test
    void countingOnlySomePartsCountsNone() {
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "CD981Y10", "Anniversary Recognition 10 Years; Laser engraved",
                "CD981YXX", "Anniversary Recognition; Laser engraved"));

        assertThat(labels).isEmpty();
    }

    @Test
    void namingOnlySomePartsByAWordIsStillFine() {
        // The documented behaviour this change leaves alone: the unnamed part keeps its code, which
        // reads no worse than before (C071A).
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "X1BL", "Award; Blue",
                "X1XX", "Award"));

        assertThat(labels).containsExactly(entry("X1BL", "Blue"));
    }

    @Test
    void theWholeOfAMixedMeasurementIsNotACount() {
        // "7 3/4\"" splits on the space into "7" and "3/4\"": that 7 is a dimension, not a count of
        // anything, and it named GM792's towers "7 Small" and "8 Med" until the unit was checked.
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "GM792A", "2 1/2\" x 7 3/4\" Tower of Facets, Small  Sandblasting",
                "GM792B", "2 3/4\" x 8 1/4\" Tower of Facets, Med  Sandblasting"));

        assertThat(labels).containsExactly(entry("GM792A", "Small"), entry("GM792B", "Med"));
    }

    @Test
    void aColourInTheDescriptionStillNamesThePart() {
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "CM711BK", "16 oz. Tumbler; Black; Laser Engraved",
                "CM711BL", "16 oz. Tumbler; Blue; Laser Engraved"));

        assertThat(labels).containsExactly(entry("CM711BK", "Black"), entry("CM711BL", "Blue"));
    }

    @Test
    void onePartDescriptionForAllOfThemNamesNone() {
        // CM371's eight leatherette colours share one description: the colour exists only on the
        // Inventory rows, which that family does not serve.
        Map<String, String> labels = CatalogService.distinguishingLabels(of(
                "CM371BS", "Leatherette Card and Dice Set; Laser Engraved",
                "CM371GR", "Leatherette Card and Dice Set; Laser Engraved"));

        assertThat(labels).isEmpty();
    }
}
