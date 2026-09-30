package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The customizer's (Trophy Options) record of one order line ({@code _trophy_items}): the artwork, and what is engraved
 * on each piece, line by line with its font. This is how PaceSetter executes the engraving, so none of
 * it may be lost or reordered on the way to the PO.
 *
 * <pre>{@code
 * {"logo":"https://…/logo.png", "preview":"https://…/ss-1790501535902.png?v=1790501537",
 *  "items":[{"item":1,"values":{"engraving":{"line-1":"textline1","line-1-font":"Open Sans",
 *                                            "line-2":"text line2 ","line-2-font":"Bebas Neue"}}}, …]}
 * }</pre>
 *
 * Read generically rather than into fixed names: any top-level text is artwork (the logo is optional),
 * any group under {@code values} is kept (engraving is the one seen so far), and a {@code <name>-<n>}
 * key pairs with its {@code <name>-<n>-font}. A product whose customizer adds a field this code has
 * never seen still gets it into the PO.
 *
 * @param artwork top-level texts — {@code logo}, {@code preview} — in the order they came
 * @param pieces  one per physical piece, in the customizer's numbering
 */
public record TrophyItem(Map<String, String> artwork, List<Piece> pieces) {

    /**
     * The property Trophy Options writes — on the order's {@code lineItemGroup}, since its cart
     * transform makes the configured product a bundle. The leading {@code _} hides it from the shopper.
     */
    public static final String KEY = "_trophy_items";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern NUMBERED = Pattern.compile("(.+?)-(\\d+)");
    private static final Pattern FONT = Pattern.compile("(.+?-\\d+)-font");
    private static final Pattern CSV_FONT = Pattern.compile("(?i)(.+?)[\\s_-]*font");
    private static final String CSV = "csv";

    /** @param number the customizer's own piece number ({@code item}), 1-based */
    public record Piece(int number, List<Text> texts) {
    }

    /**
     * @param group the {@code values} group it came from ({@code engraving})
     * @param label "Line 1" for {@code line-1}; the raw key when it is not numbered
     * @param font  null when the customizer gave none
     */
    public record Text(String group, String label, String text, String font) {
    }

    /** @throws IllegalArgumentException when the value is not the customizer's JSON */
    public static TrophyItem parse(String json) {
        return parse(json, url -> {
            throw new IllegalArgumentException("its engraving is in a CSV file, which was not downloaded");
        });
    }

    /**
     * Also reads the large-order form: past a size the customizer stops writing {@code items[]} and
     * uploads the shopper's spreadsheet instead, recording {@code {"csv":"https://cdn.shopify.com/…",
     * "count":223}} (#1053). Its rows become the pieces, so everything downstream is the same.
     *
     * @param csvLoader the file's text for its URL; throws {@link IllegalArgumentException} when it cannot
     * @throws IllegalArgumentException when the value is not the customizer's JSON, or its CSV cannot be
     *                                  read or holds a different number of pieces than it says
     */
    public static TrophyItem parse(String json, Function<String, String> csvLoader) {
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("not valid JSON (" + e.getMessage() + ")", e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        Map<String, String> artwork = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> f = it.next();
            // The CSV's URL and its count describe the pieces, not artwork for the engraver.
            if (f.getValue().isTextual() && !f.getValue().asText().isBlank() && !CSV.equals(f.getKey())) {
                artwork.put(f.getKey(), f.getValue().asText().trim());
            }
        }
        String csv = root.path(CSV).asText("").trim();
        if (!csv.isEmpty() && root.path("items").isEmpty()) {
            List<Piece> pieces = csvPieces(csvLoader.apply(csv));
            JsonNode count = root.path("count");
            if (count.canConvertToInt() && count.asInt() != pieces.size()) {
                // A short file is a truncated download or a changed upload: the PO would miss pieces.
                throw new IllegalArgumentException("its CSV has " + pieces.size() + " piece(s) but the record says "
                        + count.asInt());
            }
            return new TrophyItem(artwork, pieces);
        }
        List<Piece> pieces = new ArrayList<>();
        JsonNode items = root.path("items");
        for (int i = 0; i < items.size(); i++) {
            JsonNode item = items.get(i);
            int number = item.path("item").canConvertToInt() && item.path("item").asInt() > 0
                    ? item.path("item").asInt() : i + 1;
            List<Text> texts = new ArrayList<>();
            for (Iterator<Map.Entry<String, JsonNode>> groups = item.path("values").fields(); groups.hasNext(); ) {
                Map.Entry<String, JsonNode> group = groups.next();
                texts.addAll(texts(group.getKey(), group.getValue()));
            }
            pieces.add(new Piece(number, texts));
        }
        return new TrophyItem(artwork, pieces);
    }

    /** {@code line-2} before {@code line-10}; each with its {@code -font}, which is never a text of its own. */
    private static List<Text> texts(String group, JsonNode values) {
        if (values.isValueNode()) {
            return values.asText().isBlank() ? List.of() : List.of(new Text(group, label(group), values.asText(), null));
        }
        Map<String, String> fonts = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        values.fieldNames().forEachRemaining(keys::add);
        for (String key : keys) {
            Matcher m = FONT.matcher(key);
            if (m.matches()) {
                fonts.put(m.group(1), values.path(key).asText(null));
            }
        }
        List<String> textKeys = new ArrayList<>(keys.stream()
                .filter(k -> !FONT.matcher(k).matches() && values.path(k).isValueNode()).toList());
        textKeys.sort(Comparator.comparing((String k) -> NUMBERED.matcher(k).matches() ? 0 : 1)
                .thenComparingInt(k -> {
                    Matcher m = NUMBERED.matcher(k);
                    return m.matches() ? Integer.parseInt(m.group(2)) : 0;
                }));
        List<Text> texts = new ArrayList<>();
        for (String key : textKeys) {
            String text = values.path(key).asText("");
            if (text.isBlank()) {
                continue;   // an empty line is not something to engrave
            }
            String font = fonts.get(key);
            texts.add(new Text(group, label(key), text, font == null || font.isBlank() ? null : font));
        }
        return texts;
    }

    /**
     * The shopper's spreadsheet: a header naming the columns ({@code Line 1,Line 2}), then one row per
     * piece. A {@code <column> Font} column is that column's font (none seen yet, but it is how the
     * JSON form pairs them). A row with nothing in it is not a piece.
     */
    static List<Piece> csvPieces(String text) {
        List<List<String>> rows = csvRows(text.startsWith("﻿") ? text.substring(1) : text);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("its CSV is empty");
        }
        List<String> header = rows.get(0).stream().map(String::trim).toList();
        Map<Integer, Integer> fontOf = new LinkedHashMap<>();   // text column → its font column
        for (int c = 0; c < header.size(); c++) {
            Matcher m = CSV_FONT.matcher(header.get(c));
            if (m.matches()) {
                int column = header.indexOf(m.group(1).trim());
                if (column >= 0) {
                    fontOf.put(column, c);
                }
            }
        }
        List<Piece> pieces = new ArrayList<>();
        for (List<String> row : rows.subList(1, rows.size())) {
            if (row.stream().allMatch(String::isBlank)) {
                continue;
            }
            List<Text> texts = new ArrayList<>();
            for (int c = 0; c < header.size() && c < row.size(); c++) {
                if (fontOf.containsValue(c) || row.get(c).isBlank()) {
                    continue;
                }
                Integer f = fontOf.get(c);
                String font = f == null || f >= row.size() || row.get(f).isBlank() ? null : row.get(f).trim();
                String label = header.get(c).isEmpty() ? "Column " + (c + 1) : header.get(c);
                texts.add(new Text("engraving", label, row.get(c), font));
            }
            pieces.add(new Piece(pieces.size() + 1, texts));
        }
        return pieces;
    }

    /** RFC 4180: quoted fields may hold commas, doubled quotes and line breaks; CRLF or LF. */
    private static List<List<String>> csvRows(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }

    /** {@code line-1} → "Line 1", {@code text-box-2} → "Text box 2". */
    static String label(String key) {
        String s = key.replace('-', ' ').replace('_', ' ').trim();
        return s.isEmpty() ? key : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Every font the pieces use, in first-use order: what the engraver has to have loaded. */
    public Set<String> fonts() {
        Set<String> fonts = new LinkedHashSet<>();
        pieces.forEach(p -> p.texts().forEach(t -> {
            if (t.font() != null) {
                fonts.add(t.font());
            }
        }));
        return fonts;
    }
}
