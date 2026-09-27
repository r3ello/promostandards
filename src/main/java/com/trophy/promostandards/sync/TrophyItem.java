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
            if (f.getValue().isValueNode() && !f.getValue().asText().isBlank()) {
                artwork.put(f.getKey(), f.getValue().asText().trim());
            }
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
