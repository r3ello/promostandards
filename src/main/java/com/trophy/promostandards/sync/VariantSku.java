package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SupplierProduct.Variant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The SKU a migrated product's variants carry: the legacy catalogue's number, plus whatever
 * distinguishes one supplier part from the next.
 *
 * <pre>
 *   legacy PS1298 + {CM2541LB, CM2541LG, CM2541RB, CM2541YO, CM2541BG}
 *   -> PS1298-LB, PS1298-LG, PS1298-RB, PS1298-YO, PS1298-BG
 * </pre>
 *
 * <p>The store keeps its own numbering — the SKU is what the shop, its exports and its old system
 * recognise — while the join back to PaceSetter moves to the {@code trophy_sync.vendor_sku}
 * metafield, which holds the part id verbatim. A SKU that meant something upstream was never worth
 * much anyway: PromoStandards has no SKU field at all, so the old {@code CM373BS-2.375 X 1.75 X 1.75}
 * was this app's invention too.
 *
 * <p>The tail is the part id minus what every part shares. Two guards keep it readable: a tail that
 * does not begin with a letter (a family split like {@code CM746*}/{@code CM747*} shares only
 * {@code CM74}, leaving {@code 7BK}) and any collision fall back to the whole part id, so the SKU is
 * always unambiguous even when it is longer than it could be.
 */
final class VariantSku {

    /** Beyond this a "tail" is not a distinguishing suffix any more, it is the part id again. */
    private static final int MAX_TAIL = 6;

    private VariantSku() {
    }

    /** The key a variant is looked up by: its part id and size, which is what makes it distinct. */
    static String key(String partId, String size) {
        return (partId == null ? "" : partId.trim().toUpperCase(Locale.ROOT))
                + "|" + (size == null ? "" : size.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * @param legacySku the store product's {@code migration.legacy_sku} (PS1298); null or blank
     *                  means this product has no legacy number and keeps the supplier-derived SKUs
     * @param variants  the supplier variants the product stands for
     * @return SKU per {@link #key(String, String)}, or an empty map when there is no legacy number
     */
    static Map<String, String> byVariant(String legacySku, List<Variant> variants) {
        if (legacySku == null || legacySku.isBlank() || variants == null || variants.isEmpty()) {
            return Map.of();
        }
        String base = legacySku.trim();
        List<Variant> vs = variants.stream()
                .filter(v -> v.supplierPartId() != null && !v.supplierPartId().isBlank()).toList();
        if (vs.isEmpty()) {
            return Map.of();
        }
        if (vs.size() == 1) {
            // Nothing to distinguish: the product is the variant, and its SKU is the legacy one.
            return Map.of(key(vs.get(0).supplierPartId(), vs.get(0).size()), base);
        }

        List<String> ids = vs.stream().map(Variant::supplierPartId).toList();
        String prefix = commonPrefix(ids);
        List<String> tails = new ArrayList<>();
        for (Variant v : vs) {
            String id = v.supplierPartId();
            String tail = id.substring(Math.min(prefix.length(), id.length()));
            if (!usable(tail)) {
                tail = id;
            }
            // A part sold in several sizes is several variants under one id: without the size they
            // would share a SKU. Rare with PaceSetter (one part, one row) but not impossible.
            if (count(ids, id) > 1 && v.size() != null && !v.size().isBlank()) {
                tail = tail + "-" + v.size().trim();
            }
            tails.add(tail);
        }
        if (new HashSet<>(upper(tails)).size() != vs.size()) {
            // Still colliding: the whole part id (plus size) is the only thing guaranteed distinct.
            tails = new ArrayList<>();
            for (Variant v : vs) {
                tails.add(v.size() == null || v.size().isBlank() ? v.supplierPartId()
                        : v.supplierPartId() + "-" + v.size().trim());
            }
        }

        Map<String, String> skus = new LinkedHashMap<>();
        for (int i = 0; i < vs.size(); i++) {
            skus.putIfAbsent(key(vs.get(i).supplierPartId(), vs.get(i).size()),
                    base + "-" + tails.get(i).toUpperCase(Locale.ROOT));
        }
        return skus;
    }

    private static long count(List<String> ids, String id) {
        return ids.stream().filter(other -> other.equalsIgnoreCase(id)).count();
    }

    private static boolean usable(String tail) {
        return !tail.isEmpty() && tail.length() <= MAX_TAIL && Character.isLetter(tail.charAt(0));
    }

    private static Set<String> upper(List<String> values) {
        Set<String> out = new HashSet<>();
        for (String v : values) {
            out.add(v.toUpperCase(Locale.ROOT));
        }
        return out;
    }

    /** Case-insensitive longest common prefix, returned with the first id's casing. */
    private static String commonPrefix(List<String> ids) {
        String prefix = ids.get(0);
        for (String id : ids) {
            int i = 0;
            int max = Math.min(prefix.length(), id.length());
            while (i < max && Character.toUpperCase(prefix.charAt(i)) == Character.toUpperCase(id.charAt(i))) {
                i++;
            }
            prefix = prefix.substring(0, i);
            if (prefix.isEmpty()) {
                return "";
            }
        }
        return prefix;
    }
}
