package com.trophy.promostandards.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Real PaceSetter names from the 2026-09-16 batch of products new to the store. */
class StoreHandleTest {

    @Test
    void numbersTheNameLikeTheStoresOwnHandles() {
        assertThat(StoreHandle.of("p", 10500, "Classic Leatherette on Black Plaque - Small"))
                .isEqualTo("p-10500-classic-leatherette-on-black-plaque-small");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', quoteCharacter = '~', value = {
            "producto lindo 3x4                                         | producto-lindo",
            "8 x 10 Black Soft Edge Plaque with Silver Plate            | black-soft-edge-plaque-with-silver-plate",
            "Laser-Cut Lucite Contour 1/4\" Thick Up To 23 Sq In        | laser-cut-lucite-contour-thick",
            "3\" x 2 3/4\"Crystal Heart Shaped Christmas Ornament       | crystal-heart-shaped-christmas-ornament",
            "Polar Camel Slider Lid 12 oz 14 oz or 16 oz                | polar-camel-slider-lid",
            "20 oz Polar Camel Travel Mug, Blue                         | polar-camel-travel-mug-blue",
            "Anniversary Achievement Award, 1 Year                      | anniversary-achievement-award",
            "Rosewood Finish 5-Piece Wine Tool Gift Set                 | rosewood-finish-wine-tool-gift-set",
            "Custom Phone Holder up to 20 Sq.Inches                     | custom-phone-holder",
            "Square Glass Decanter Set with Four 11 Oz.. Glasse         | square-glass-decanter-set-with-four-glasse",
            "Crystal Wave Award with Silver Star on Black 9.25\"        | crystal-wave-award-with-silver-star-on-black",
            "Optic Clear and Blue Crystal on Clear Base (Includes Sandblast in 2 Locations and Silver Color-Fill on Blue) "
                    + "| optic-clear-and-blue-crystal-on-clear-base-includes-sandblast-in-locations-and-silver-color-fill-on-blue",
            "Small Beveled Oval, Black                                  | small-beveled-oval-black",
            "Black and Silver Cloisonné Oval Date Bar-Adhesive          | black-and-silver-cloisonne-oval-date-bar-adhesive",
            "⅜\" Thick Lucite Wall Plaque with Custom Digi-Color       | thick-lucite-wall-plaque-with-custom-digi-color",
            "Men's Walnut Plaque w/ Choice Of Plate & Board             | mens-walnut-plaque-w-choice-of-plate-board",
    })
    void dropsMeasurementsAndWhatTheyLeaveDangling(String title, String expected) {
        assertThat(StoreHandle.name(title)).isEqualTo(expected);
    }

    @Test
    void aNameMadeOnlyOfMeasurementsLeavesTheNumber() {
        assertThat(StoreHandle.of("p", 10501, "12 x 9")).isEqualTo("p-10501");
    }

    @Test
    void cutsAnOverlongNameOnAWordBoundary() {
        String full = "floating-coin-plastic-holder-".repeat(12);
        String name = StoreHandle.name("Floating coin plastic holder ".repeat(12));
        assertThat(name.length()).isLessThanOrEqualTo(200);
        assertThat(full).startsWith(name + "-");     // whole words only, nothing half cut
    }

    @Test
    void readsTheNumberBackOnlyUnderItsOwnPrefix() {
        assertThat(StoreHandle.number("p", "p-10500-crystal-star")).isEqualTo(10500);
        assertThat(StoreHandle.number("p", "p-10501")).isEqualTo(10501);
        assertThat(StoreHandle.number("p", "ps-pacesetter-cm373bs")).isEqualTo(-1);
        assertThat(StoreHandle.number("p", "q-10500-crystal-star")).isEqualTo(-1);
        assertThat(StoreHandle.number("p", null)).isEqualTo(-1);
    }
}
