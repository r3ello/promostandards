package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SupplierProduct.Variant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Shopify option labels for supplier variants.
 *
 * <p>Shopify identifies a variant by its combination of option values, so no two variants of a
 * product may share one. PaceSetter's colours do collide: the CM297 portfolio family lists
 * {@code CM297BL} and {@code CM297LB} both as "Dark Brown / 12 X 9.5" (likewise {@code CK}/{@code PK}
 * as "Grey"). When a colour collides, every variant of that colour is suffixed with the
 * distinguishing tail of its part id — "Dark Brown (BL)", "Dark Brown (LB)" — which keeps the option
 * readable, the variants distinct, and each part's stock on its own variant instead of merged.
 *
 * <p>A variant with no colour at all (PaceSetter answers "N/A") is named by its {@code label} — what
 * its description says that the other parts' do not ("Black", "Small") — and, failing that, by its
 * part code alone: "BS", never "Default (BS)".
 */
final class VariantOptions {

    static final String COLOR = "Color";
    static final String SIZE = "Size";
    static final String YEAR = "Year";
    static final String DEFAULT_COLOR = "Default";
    static final String DEFAULT_SIZE = "One Size";

    private VariantOptions() {
    }

    /** The Color option value for a variant colour, with the placeholder for "no colour given". */
    static String color(String color) {
        return color == null || color.isBlank() ? DEFAULT_COLOR : color;
    }

    /** The Size option value for a variant size, with the placeholder for "no size given". */
    static String size(String size) {
        return size == null || size.isBlank() ? DEFAULT_SIZE : size;
    }

    /** Whether the product needs a Size option at all. */
    static boolean hasSize(List<Variant> variants) {
        return variants.stream().anyMatch(v -> v.size() != null && !v.size().isBlank());
    }

    /**
     * @return one Color option value per variant, in the same order as {@code variants}, suffixed
     * with the part id's distinguishing tail wherever two variants would otherwise claim the same
     * (colour, size) combination.
     */
    static List<String> colorLabels(List<Variant> variants) {
        List<String> labels = new ArrayList<>(variants.size());
        for (Variant v : variants) {
            labels.add(color(v.color() != null && !v.color().isBlank() ? v.color() : v.label()));
        }

        // A collision is two variants sharing (colour, size); disambiguate every variant of that
        // colour, not just the colliding pair, so one colour reads the same way across sizes.
        Map<String, Integer> firstByCombo = new LinkedHashMap<>();
        Set<String> ambiguous = new LinkedHashSet<>();
        for (int i = 0; i < variants.size(); i++) {
            String combo = key(labels.get(i)) + "|" + key(size(variants.get(i).size()));
            Integer first = firstByCombo.putIfAbsent(combo, i);
            if (first != null) {
                ambiguous.add(key(labels.get(i)));
                ambiguous.add(key(labels.get(first)));
            }
        }
        // "Default" next to named variants (Black, Default, Blue) reads as a colour called Default:
        // there the unnamed one takes its code too. Only when NO variant has a name does "Default"
        // stay, e.g. one part sold in several sizes, where it is the product's only Color value.
        boolean anyNamed = labels.stream().anyMatch(l -> !key(l).equals(key(DEFAULT_COLOR)));
        boolean anyUnnamed = labels.stream().anyMatch(l -> key(l).equals(key(DEFAULT_COLOR)));
        if (anyNamed && anyUnnamed) {
            ambiguous.add(key(DEFAULT_COLOR));
        }
        for (String colour : ambiguous) {
            applySuffixes(variants, labels, colour);
        }
        return labels;
    }

    /**
     * The years of a years-of-service family, or {@code null} when {@code variants} is not one.
     *
     * <p>PaceSetter sells an anniversary award as one part per year — {@code CD902Y1}, {@code CD902Y5}
     * … {@code CD902Y50} — and describes them all alike. Its Product Data names no colour, so the
     * parts it also reports stock for were labelled from their inventory row ("Black Frost (10) /
     * 9 X 7 X 0.875") and the rest from their description ("1 / One Size"): one selector, two naming
     * schemes, and the year buried in a suffix. The year is the only thing the shopper chooses, so a
     * family like this gets a single {@code Year} option valued with the number alone.
     *
     * <p>A family is one where every part id is a shared stem ending in {@code Y} plus a distinct
     * number. The shared stem is backed off past any digit the numbers happen to share ({@code Y1},
     * {@code Y10}, {@code Y15} share {@code Y1}). Anything else — one part, a part sold in several
     * sizes (two variants, one year), a stem not ending in {@code Y} — is not a family.
     */
    static List<String> yearLabels(List<Variant> variants) {
        if (variants.size() < 2) {
            return null;
        }
        List<String> partIds = new ArrayList<>(variants.size());
        for (Variant v : variants) {
            if (v.supplierPartId() == null || v.supplierPartId().isBlank()) {
                return null;
            }
            partIds.add(v.supplierPartId().trim());
        }
        String first = partIds.get(0);
        int prefix = commonPrefixLength(partIds);
        while (prefix > 0 && Character.isDigit(first.charAt(prefix - 1))) {
            prefix--;
        }
        if (prefix < 2 || Character.toUpperCase(first.charAt(prefix - 1)) != 'Y') {
            return null;
        }
        List<String> years = new ArrayList<>(partIds.size());
        Set<String> seen = new LinkedHashSet<>();
        for (String partId : partIds) {
            String tail = partId.substring(prefix);
            if (!tail.matches("\\d{1,3}")) {
                return null;
            }
            String year = String.valueOf(Integer.parseInt(tail));
            if (!seen.add(year)) {
                return null;
            }
            years.add(year);
        }
        return years;
    }

    /** Suffixes every label of one ambiguous colour with what its part id adds over the others. */
    private static void applySuffixes(List<Variant> variants, List<String> labels, String colour) {
        List<Integer> indexes = new ArrayList<>();
        List<String> partIds = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) {
            if (key(labels.get(i)).equals(colour)) {
                if (variants.get(i).supplierPartId() == null) {
                    return;     // nothing to disambiguate with; leave the labels alone
                }
                indexes.add(i);
                partIds.add(variants.get(i).supplierPartId());
            }
        }
        // An unnamed variant's code is what its part id adds over EVERY part of the product (CM731BKRG
        // among CM731BK, CM731BL… reads "BKRG"), not just over the other unnamed ones — alone, it
        // would share its whole id with itself and show the full part id.
        List<String> basis = partIds;
        if (colour.equals(key(DEFAULT_COLOR))) {
            basis = new ArrayList<>();
            for (Variant v : variants) {
                if (v.supplierPartId() != null) {
                    basis.add(v.supplierPartId());
                }
            }
        }
        int prefix = commonPrefixLength(basis);
        for (String partId : partIds) {
            if (partId.length() <= prefix) {
                prefix = 0;     // one id is a prefix of another (EP2 / EP2PK): use the ids whole
                break;
            }
        }
        for (int n = 0; n < indexes.size(); n++) {
            int i = indexes.get(n);
            String tail = partIds.get(n).substring(prefix);
            // A variant with no name at all is just its code: "Default (BS)" says nothing "BS" does not.
            labels.set(i, key(labels.get(i)).equals(key(DEFAULT_COLOR)) ? tail
                    : labels.get(i) + " (" + tail + ")");
        }
    }

    private static int commonPrefixLength(List<String> values) {
        if (values.isEmpty()) {
            return 0;
        }
        int prefix = values.get(0).length();
        for (String value : values) {
            int i = 0;
            while (i < prefix && i < value.length()
                    && Character.toUpperCase(value.charAt(i)) == Character.toUpperCase(values.get(0).charAt(i))) {
                i++;
            }
            prefix = i;
        }
        return prefix;
    }

    /** Option values are compared case-insensitively, the way Shopify treats them. */
    static String key(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
