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
 */
final class VariantOptions {

    static final String COLOR = "Color";
    static final String SIZE = "Size";
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
            labels.add(color(v.color()));
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
        for (String colour : ambiguous) {
            applySuffixes(variants, labels, colour);
        }
        return labels;
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
        int prefix = commonPrefixLength(partIds);
        for (String partId : partIds) {
            if (partId.length() <= prefix) {
                prefix = 0;     // one id is a prefix of another (EP2 / EP2PK): use the ids whole
                break;
            }
        }
        for (int n = 0; n < indexes.size(); n++) {
            int i = indexes.get(n);
            labels.set(i, labels.get(i) + " (" + partIds.get(n).substring(prefix) + ")");
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
