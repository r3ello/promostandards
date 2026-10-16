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
    static final String STATE = "State";
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

    /** US state (and DC) postal codes, by name — the State option's values. */
    private static final Map<String, String> STATES = Map.ofEntries(
            Map.entry("AL", "Alabama"), Map.entry("AK", "Alaska"), Map.entry("AZ", "Arizona"),
            Map.entry("AR", "Arkansas"), Map.entry("CA", "California"), Map.entry("CO", "Colorado"),
            Map.entry("CT", "Connecticut"), Map.entry("DE", "Delaware"), Map.entry("DC", "District of Columbia"),
            Map.entry("FL", "Florida"), Map.entry("GA", "Georgia"), Map.entry("HI", "Hawaii"),
            Map.entry("ID", "Idaho"), Map.entry("IL", "Illinois"), Map.entry("IN", "Indiana"),
            Map.entry("IA", "Iowa"), Map.entry("KS", "Kansas"), Map.entry("KY", "Kentucky"),
            Map.entry("LA", "Louisiana"), Map.entry("ME", "Maine"), Map.entry("MD", "Maryland"),
            Map.entry("MA", "Massachusetts"), Map.entry("MI", "Michigan"), Map.entry("MN", "Minnesota"),
            Map.entry("MS", "Mississippi"), Map.entry("MO", "Missouri"), Map.entry("MT", "Montana"),
            Map.entry("NE", "Nebraska"), Map.entry("NV", "Nevada"), Map.entry("NH", "New Hampshire"),
            Map.entry("NJ", "New Jersey"), Map.entry("NM", "New Mexico"), Map.entry("NY", "New York"),
            Map.entry("NC", "North Carolina"), Map.entry("ND", "North Dakota"), Map.entry("OH", "Ohio"),
            Map.entry("OK", "Oklahoma"), Map.entry("OR", "Oregon"), Map.entry("PA", "Pennsylvania"),
            Map.entry("RI", "Rhode Island"), Map.entry("SC", "South Carolina"), Map.entry("SD", "South Dakota"),
            Map.entry("TN", "Tennessee"), Map.entry("TX", "Texas"), Map.entry("UT", "Utah"),
            Map.entry("VT", "Vermont"), Map.entry("VA", "Virginia"), Map.entry("WA", "Washington"),
            Map.entry("WV", "West Virginia"), Map.entry("WI", "Wisconsin"), Map.entry("WY", "Wyoming"));

    /** A family needs this many states before its codes are read as states rather than colours. */
    private static final int MIN_STATES = 10;

    /**
     * The states of a state-cutout family, or {@code null} when {@code variants} is not one.
     *
     * <p>PaceSetter sells a state-shaped award as one part per state — {@code CD900AK}, {@code CD900AL}
     * … {@code CD900WY}, plus a few stock shapes ({@code CD900OVAL}, {@code CD900STAR},
     * {@code CD900USA}) — and gives them all one colour, "Clear Black". As Color they read "Clear
     * Black (AK)", "Clear Black (IN)"…; the state is the only thing the shopper chooses, so the family
     * gets a single {@code State} option valued with the state's name.
     *
     * <p>A family is one stem ending in a digit, the same for every part, followed by letters; at least
     * {@value #MIN_STATES} parts and four in five must be state codes, so a colour family whose codes
     * happen to include {@code OR} or {@code IN} is never taken for one. Other tails are stock shapes,
     * named by their code in title case ({@code OVAL} → "Oval"; {@code US}/{@code USA} → "USA").
     */
    static List<String> stateLabels(List<Variant> variants) {
        if (variants.size() < MIN_STATES) {
            return null;
        }
        Map<String, Integer> stems = new LinkedHashMap<>();
        for (Variant v : variants) {
            String id = v.supplierPartId();
            if (id == null || id.trim().length() < 3) {
                return null;
            }
            id = id.trim().toUpperCase(Locale.ROOT);
            stems.merge(id.substring(0, id.length() - 2), 1, Integer::sum);
        }
        String stem = stems.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
        if (stem.isEmpty() || !Character.isDigit(stem.charAt(stem.length() - 1))) {
            return null;
        }
        List<String> labels = new ArrayList<>(variants.size());
        Set<String> seen = new LinkedHashSet<>();
        int states = 0;
        for (Variant v : variants) {
            String id = v.supplierPartId().trim().toUpperCase(Locale.ROOT);
            String tail = id.startsWith(stem) ? id.substring(stem.length()) : "";
            if (!tail.matches("[A-Z]{2,}")) {
                return null;
            }
            String label = STATES.get(tail);
            if (label != null) {
                states++;
            } else {
                label = tail.equals("US") || tail.equals("USA") ? "USA"
                        : tail.charAt(0) + tail.substring(1).toLowerCase(Locale.ROOT);
            }
            if (!seen.add(key(label))) {
                return null;
            }
            labels.add(label);
        }
        return states >= MIN_STATES && states * 5 >= variants.size() * 4 ? labels : null;
    }

    /**
     * The one option a family is chosen by, when it is one: years ({@link #yearLabels}) or states
     * ({@link #stateLabels}); {@code null} for everything else, which keeps Color/Size.
     *
     * @param name   the option's name
     * @param labels one value per variant, in the order of {@code variants}
     * @param order  the distinct values in the order the selector should list them
     */
    record SingleOption(String name, List<String> labels, List<String> order) {
    }

    static SingleOption singleOption(List<Variant> variants) {
        List<String> years = yearLabels(variants);
        if (years != null) {
            return new SingleOption(YEAR, years, years.stream().distinct()
                    .sorted(java.util.Comparator.comparingInt(Integer::parseInt)).toList());
        }
        List<String> states = stateLabels(variants);
        if (states != null) {
            // States alphabetically, then the stock shapes (Oval, Star, USA).
            java.util.Comparator<String> byKind = java.util.Comparator.comparing(
                    l -> !STATES.containsValue(l));
            return new SingleOption(STATE, states, states.stream().distinct()
                    .sorted(byKind.thenComparing(java.util.Comparator.naturalOrder())).toList());
        }
        return null;
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
