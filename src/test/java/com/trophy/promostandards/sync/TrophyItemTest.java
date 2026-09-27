package com.trophy.promostandards.sync;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The customizer's {@code _trophy_items}, read as order #1049 on the dev store carries it. */
class TrophyItemTest {

    /** Order #1049 (GM828 × 3), as the admin shows it on 2026-09-27. */
    static final String ORDER_1049 = """
            {"logo":"https://cdn.shopify.com/s/files/1/0655/6231/2798/files/trophy-logo-p-8159-redblack-spiral-teardrop-art-glass-1790501508735.jpg?v=1790501510",
             "preview":"https://cdn.shopify.com/s/files/1/0655/6231/2798/files/trophy-preview-p-8159-redblack-spiral-teardrop-art-glass-1790501535902.png?v=1790501537",
             "items":[{"item":1,"values":{"engraving":{"line-1":"textline1","line-1-font":"Open Sans","line-2":"text line2 ","line-2-font":"Bebas Neue"}}},
                      {"item":2,"values":{"engraving":{"line-1":"test 1","line-1-font":"Open Sans","line-2":"test 2","line-2-font":"Open Sans"}}},
                      {"item":3,"values":{"engraving":{"line-1":"line1","line-1-font":"Open Sans","line-2":"line2","line-2-font":"Open Sans"}}}]}""";

    @Test
    void readsArtworkAndEveryPieceWithItsFonts() {
        TrophyItem item = TrophyItem.parse(ORDER_1049);

        assertThat(item.artwork()).containsOnlyKeys("logo", "preview");
        assertThat(item.pieces()).extracting(TrophyItem.Piece::number).containsExactly(1, 2, 3);
        assertThat(item.pieces().get(0).texts()).containsExactly(
                new TrophyItem.Text("engraving", "Line 1", "textline1", "Open Sans"),
                new TrophyItem.Text("engraving", "Line 2", "text line2 ", "Bebas Neue"));
        assertThat(item.fonts()).containsExactly("Open Sans", "Bebas Neue");
    }

    /** Line 10 after line 2, a blank line skipped, a line with no font kept, and the logo optional. */
    @Test
    void ordersLinesByNumberAndToleratesWhatIsMissing() {
        TrophyItem item = TrophyItem.parse("""
                {"preview":"https://x/p.png","items":[{"item":1,"values":{"engraving":{
                  "line-10":"ten","line-2":"two","line-2-font":"Arial","line-3":"  ","line-1":"one"}}},
                  {"values":{}}]}""");

        assertThat(item.artwork()).containsOnlyKeys("preview");
        assertThat(item.pieces().get(0).texts()).extracting(TrophyItem.Text::label, TrophyItem.Text::font)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Line 1", null),
                        org.assertj.core.groups.Tuple.tuple("Line 2", "Arial"),
                        org.assertj.core.groups.Tuple.tuple("Line 10", null));
        assertThat(item.pieces().get(1).number()).isEqualTo(2);   // numbered by position when unnumbered
        assertThat(item.pieces().get(1).texts()).isEmpty();
    }

    @Test
    void refusesWhatIsNotTheCustomizersJson() {
        assertThatThrownBy(() -> TrophyItem.parse("{\"items\":[")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrophyItem.parse("[1,2]")).isInstanceOf(IllegalArgumentException.class);
    }
}
