package com.trophy.promostandards.sync;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The handle of a product this app creates: {@code p-<number>-<name>}, the shape every product in the
 * store already has ({@code p-5806-alabama-state-silhouette-awards}).
 *
 * <p>The name is the product's title <b>without its measurements</b> (client requirement,
 * 2026-09-16): PaceSetter writes sizes into its names — {@code Laser-Cut Lucite Contour 1/4" Thick Up
 * To 23 Sq In} — and a handle is a URL, not a spec sheet. So every word carrying a digit goes, with the
 * unit that followed it ({@code oz}, {@code sq in}, the {@code x} of {@code 8 x 10}), and so does a
 * connecting word left dangling at the end ({@code up to}, {@code or}). The number in front already
 * makes the handle unique; two sizes of one product may share the rest.
 *
 * <p>Quotes and apostrophes are dropped rather than turned into a separator, as the store's own
 * handles do ({@code 3/4"} was {@code 34} there), and accents come off their letters
 * ({@code Cloisonné} is {@code cloisonne}, not {@code cloisonn}); anything else that is not a letter
 * or a digit separates words.
 */
final class StoreHandle {

    /** Shopify's own limit is 255; the cut lands on a word boundary well inside it. */
    private static final int MAX_LENGTH = 200;

    /** A word that only means something next to the number it followed. */
    private static final Set<String> UNITS = Set.of("x", "oz", "in", "inch", "inches", "ft", "feet",
            "mm", "cm", "lb", "lbs", "sq", "qt", "ml", "gal", "pc", "pcs", "pk", "yr", "yrs", "year",
            "years", "plt");

    /** Words that only join two others, so mean nothing at the end of a name. */
    private static final Set<String> DANGLING = Set.of("x", "by", "to", "up", "or", "and", "with", "of",
            "on", "in", "for", "a", "the", "w");

    private static final Pattern NUMBERED = Pattern.compile("^(.+?)-(\\d+)(?:-|$)");

    private StoreHandle() {
    }

    /** @return {@code <prefix>-<number>-<name>}, or {@code <prefix>-<number>} when no name is left. */
    static String of(String prefix, int number, String title) {
        String name = name(title);
        return name.isEmpty() ? prefix + "-" + number : prefix + "-" + number + "-" + name;
    }

    /**
     * @return the number a handle of this shape carries under {@code prefix}, or {@code -1} when the
     * handle is not one ({@code p-10500-crystal-star} → 10500)
     */
    static int number(String prefix, String handle) {
        if (handle == null) {
            return -1;
        }
        Matcher m = NUMBERED.matcher(handle);
        if (!m.find() || !m.group(1).equalsIgnoreCase(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The title reduced to its words, measurements removed, as a handle segment. */
    static String name(String title) {
        if (title == null) {
            return "";
        }
        String text = Normalizer.normalize(title.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                // A quote glued to the next word ends a measurement: 3/4"Crystal is "3/4" + Crystal.
                .replaceAll("[\"”″](?=\\p{L})", " ")
                .replaceAll("[\"'’”″`]", "");
        List<String> kept = new ArrayList<>();
        boolean afterNumber = false;
        // Dots split too, so "Sq.Inches" is two units and "9.25" two numbers.
        for (String token : text.split("[\\s.]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.chars().anyMatch(Character::isDigit)) {
                afterNumber = true;
                continue;
            }
            String word = token.replaceAll("[^\\p{L}]", "");
            if (afterNumber && UNITS.contains(word)) {
                continue;
            }
            afterNumber = false;
            kept.add(token);
        }
        List<String> words = new ArrayList<>(Arrays.asList(
                String.join(" ", kept).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+)|(-+$)", "")
                        .split("-")));
        words.removeIf(String::isEmpty);
        while (!words.isEmpty() && DANGLING.contains(words.get(words.size() - 1))) {
            words.remove(words.size() - 1);
        }
        String name = String.join("-", words);
        if (name.length() > MAX_LENGTH) {
            // Cut on a word boundary: at the limit when a word ends exactly there, else before it.
            int cut = name.charAt(MAX_LENGTH) == '-' ? MAX_LENGTH : name.lastIndexOf('-', MAX_LENGTH);
            name = name.substring(0, cut > 0 ? cut : MAX_LENGTH);
        }
        return name;
    }
}
