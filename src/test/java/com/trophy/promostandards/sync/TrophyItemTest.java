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

    /**
     * Order #1063 (Como Goal-Setter × 3, 2026-10-19), the customizer's current form: preview and proof for
     * the line, upcharges, and per piece the product plus five blocks, each with its own Line 1. Piece 1
     * is as the order has it; piece 2 adds the optional per-piece logo.
     */
    static final String ORDER_1063 = """
            {"preview":"https://cdn.shopify.com/s/files/1/0655/6231/2798/files/trophy-preview-p-3665-como-goal-setter-crystal-awards-1792443002293.jpg?v=1792443004",
             "proof":"https://cdn.shopify.com/s/files/1/0655/6231/2798/files/trophy-technical-sheet-p-3665-como-goal-setter-crystal-awards-1792443010291.pdf?v=1792443011",
             "upcharges":[{"label":"Choose Number of Goal-Setter Blocks","amount":92,"once":false,"choice":"5 Blocks"},{"label":"Custom Design","amount":1,"once":false}],
             "items":[{"item":1,"values":{
                 "engraving-product":{"line-1":"Winner ","line-1-font":"Great Vibes","line-2":"Mattt","line-2-font":"Cinzel","line-3":"Gunn","line-3-font":"Cinzel","line-4":"2026","line-4-font":"Open Sans"},
                 "engraving-block":{"line-1":"1,000,000","line-1-font":"Great Vibes"},
                 "engraving-block-2":{"line-1":"2,000,000","line-1-font":"Great Vibes"},
                 "engraving-block-5":{"line-1":"5,000,000","line-1-font":"Great Vibes"}}},
              {"item":2,"logo":"https://cdn.shopify.com/s/files/1/0655/6231/2798/uploads/6e76ba4bb68b45b13986e4b1d3b91547.svg","values":{
                 "engraving-product":{"line-1":"Runner Up ","line-2":"Ralph","line-1-font":"Great Vibes","line-2-font":"Cinzel"}}}]}""";

    @Test
    void namesTheSurfaceEachTextGoesOnAndKeepsAPiecesOwnLogo() {
        TrophyItem item = TrophyItem.parse(ORDER_1063);

        assertThat(item.artwork()).containsOnlyKeys("preview", "proof");   // upcharges is not artwork
        assertThat(item.pieces().get(0).texts()).extracting(TrophyItem.Text::label, TrophyItem.Text::text)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Line 1", "Winner "),
                        org.assertj.core.groups.Tuple.tuple("Line 2", "Mattt"),
                        org.assertj.core.groups.Tuple.tuple("Line 3", "Gunn"),
                        org.assertj.core.groups.Tuple.tuple("Line 4", "2026"),
                        org.assertj.core.groups.Tuple.tuple("Block 1 - Line 1", "1,000,000"),
                        org.assertj.core.groups.Tuple.tuple("Block 2 - Line 1", "2,000,000"),
                        org.assertj.core.groups.Tuple.tuple("Block 5 - Line 1", "5,000,000"));
        assertThat(item.pieces().get(1).texts().get(0)).isEqualTo(new TrophyItem.Text(TrophyItem.ARTWORK, "Logo",
                "https://cdn.shopify.com/s/files/1/0655/6231/2798/uploads/6e76ba4bb68b45b13986e4b1d3b91547.svg", null));
        assertThat(item.fonts()).containsExactly("Great Vibes", "Cinzel", "Open Sans");
    }

    @Test
    void refusesWhatIsNotTheCustomizersJson() {
        assertThatThrownBy(() -> TrophyItem.parse("{\"items\":[")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TrophyItem.parse("[1,2]")).isInstanceOf(IllegalArgumentException.class);
    }

    /** The large-order form: a header, one row per piece, a font column paired by name, blank rows skipped. */
    @Test
    void readsTheSpreadsheetForm() {
        TrophyItem item = TrophyItem.parse("{\"preview\":\"https://cdn.shopify.com/p.png\",\"csv\":\"https://cdn.shopify.com/a.csv\",\"count\":2}",
                url -> "Line 1,Line 1 Font,Line 2\nAnn,Open Sans,\"say \"\"hi\"\"\"\n,,\nBob,,Two\n");

        assertThat(item.artwork()).containsOnlyKeys("preview");
        assertThat(item.pieces()).hasSize(2);
        assertThat(item.pieces().get(0).texts()).containsExactly(
                new TrophyItem.Text("engraving", "Line 1", "Ann", "Open Sans"),
                new TrophyItem.Text("engraving", "Line 2", "say \"hi\"", null));
        assertThat(item.pieces().get(1).number()).isEqualTo(2);
    }

    /** #1055: the spreadsheet holds only text; the record's fonts apply to their column on every piece. */
    @Test
    void appliesTheRecordsFontsToTheSpreadsheetsColumns() {
        TrophyItem item = TrophyItem.parse("{\"csv\":\"https://cdn.shopify.com/a.csv\",\"count\":2,"
                        + "\"fonts\":{\"line-1-font\":\"Roboto\"}}",
                url -> "﻿Line 1,Line 2,Line 3\r\nSchool of Arts,Ralph B. Lara,2026\r\nSchool of Arts,Pablo R. Yordy,2026");

        assertThat(item.artwork()).isEmpty();
        assertThat(item.pieces()).hasSize(2);
        assertThat(item.pieces().get(1).texts()).containsExactly(
                new TrophyItem.Text("engraving", "Line 1", "School of Arts", "Roboto"),
                new TrophyItem.Text("engraving", "Line 2", "Pablo R. Yordy", null),
                new TrophyItem.Text("engraving", "Line 3", "2026", null));
        assertThat(item.fonts()).containsExactly("Roboto");
    }

    /** Fewer rows than the record says is a truncated or changed file: the PO would miss pieces. */
    @Test
    void refusesASpreadsheetShorterThanItsCount() {
        assertThatThrownBy(() -> TrophyItem.parse("{\"csv\":\"https://cdn.shopify.com/a.csv\",\"count\":223}",
                url -> "Line 1\nAnn\n"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("1 piece(s) but the record says 223");
    }
}
