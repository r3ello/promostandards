package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.CatalogStatusView.Category;
import com.trophy.promostandards.sync.model.CatalogStatusView.Entry;
import com.trophy.promostandards.sync.model.CatalogStatusView.SupplierEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Sorts the store's PromoStandards products by where they stand against PaceSetter. Pure: every
 * supplier and store answer comes in as data, so each rule is testable on its own.
 *
 * <p>The rules are {@code tools/simulate_store_sync.py}'s, the dry run every bulk pass of this
 * project has been checked against. What a store code <em>is</em> at PaceSetter:
 * <ul>
 *   <li><b>product</b> — sellable, with product data and a price: a sync can run on it;</li>
 *   <li><b>no price</b> / <b>no product data</b> — sellable, but one of the two missing: the sync
 *       refuses it (no prices means published at no price, no parts means no variants);</li>
 *   <li><b>part</b> — not a product but a variant of one: listed by another product's product
 *       data, price table or Inventory answer (CM713BL is a colour of CM713*);</li>
 *   <li><b>family part</b> — falls under a wildcard code PaceSetter sells (CM713 under CM713*);</li>
 *   <li><b>orphan</b> — no trace at all: discontinued or recoded.</li>
 * </ul>
 * One difference from the script: a code that is not in the sellable list is never read, so a
 * product PaceSetter still answers for but no longer lists counts as gone (the script found none).
 */
final class CatalogStatusClassifier {

    static final String DISCONTINUED_TAG = "pacesetter-discontinued";

    /** A store product as the classifier needs it. {@code supplierIds} are as the store lists them. */
    record StoreProduct(String gid, String handle, String title, String status, List<String> tags,
                        List<String> supplierIds, String lastSyncAt, String imageUrl, String groupedInto) {
    }

    /**
     * What PaceSetter answered. Every map is keyed by sellable id; an id is absent when the answer
     * was a failure or empty.
     *
     * @param productParts   part ids of each id's product data, for the ids that have parts
     * @param names          product name per id
     * @param pricedParts    part ids with at least one price break, for the ids that have a price
     * @param inventoryParts part ids each id's Inventory answer lists (a whole family, sometimes)
     */
    record Supplier(List<String> sellable, Map<String, List<String>> productParts, Map<String, String> names,
                    Map<String, List<String>> pricedParts, Map<String, List<String>> inventoryParts) {
    }

    record Result(List<Entry> products, List<SupplierEntry> newAtSupplier, List<SupplierEntry> related,
                  int notImportable) {
    }

    enum Kind { PRODUCT, NO_PRICE, NO_PRODUCT_DATA, PART, FAMILY_PART, ORPHAN }

    private final Supplier supplier;
    private final Set<String> sellable;
    private final Map<String, List<String>> productParts;
    private final Map<String, List<String>> pricedParts;
    private final Map<String, List<String>> inventoryParts;
    private final Map<String, String> names;
    /** Wildcard families PaceSetter sells (CM713 for CM713*), longest first. */
    private final List<String> families;
    /** Part -> the products that declare it in product data or a price table. */
    private final Map<String, Set<String>> declaredOwners = new HashMap<>();
    /** Part -> the products whose Inventory answer lists it. */
    private final Map<String, Set<String>> inventoryOwners = new HashMap<>();

    private CatalogStatusClassifier(Supplier supplier) {
        this.supplier = supplier;
        this.sellable = supplier.sellable().stream().map(CatalogStatusClassifier::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        this.productParts = upperKeys(supplier.productParts());
        this.pricedParts = upperKeys(supplier.pricedParts());
        this.inventoryParts = upperKeys(supplier.inventoryParts());
        this.names = new HashMap<>();
        supplier.names().forEach((id, name) -> names.put(key(id), name));
        this.families = sellable.stream().filter(s -> s.endsWith("*"))
                .map(s -> s.substring(0, s.length() - 1))
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
        for (String s : sellable) {
            for (String part : concat(productParts.get(s), pricedParts.get(s))) {
                if (!part.equals(s)) {
                    declaredOwners.computeIfAbsent(part, k -> new TreeSet<>()).add(s);
                }
            }
            for (String part : nonNull(inventoryParts.get(s))) {
                if (!part.equals(s)) {
                    inventoryOwners.computeIfAbsent(part, k -> new TreeSet<>()).add(s);
                }
            }
        }
    }

    static Result classify(List<StoreProduct> store, Supplier supplier) {
        return new CatalogStatusClassifier(supplier).run(store);
    }

    private Result run(List<StoreProduct> store) {
        // Who lists each code, and which variants a sync of each product would bring in.
        Map<String, List<StoreProduct>> claims = new HashMap<>();
        for (StoreProduct p : store) {
            for (String id : p.supplierIds()) {
                claims.computeIfAbsent(key(id), k -> new ArrayList<>()).add(p);
            }
        }
        Map<StoreProduct, Set<String>> variantsOf = new HashMap<>();
        Map<String, Set<String>> variantHolders = new HashMap<>();
        for (StoreProduct p : store) {
            if (isArchived(p)) {
                continue;
            }
            Set<String> variants = new TreeSet<>();
            for (String id : p.supplierIds()) {
                if (kind(id) == Kind.PRODUCT) {
                    variants.addAll(variantParts(key(id)));
                }
            }
            variantsOf.put(p, variants);
            for (String v : variants) {
                variantHolders.computeIfAbsent(v, k -> new TreeSet<>()).add(p.handle());
            }
        }
        // PaceSetter product -> the store products listing its parts (the split ones).
        Map<String, Set<String>> partHolders = new HashMap<>();
        for (StoreProduct p : store) {
            for (String id : p.supplierIds()) {
                if (kind(id) == Kind.PART) {
                    for (String owner : owners(key(id))) {
                        partHolders.computeIfAbsent(owner, k -> new TreeSet<>()).add(p.handle());
                    }
                }
            }
        }

        List<Entry> entries = new ArrayList<>();
        for (StoreProduct p : store) {
            entries.add(entry(p, claims, variantsOf, variantHolders, partHolders));
        }
        entries.sort(Comparator.comparing(Entry::category)
                .thenComparing(e -> e.title() == null ? "" : e.title().toLowerCase(Locale.ROOT)));

        List<SupplierEntry> fresh = new ArrayList<>();
        List<SupplierEntry> related = new ArrayList<>();
        int notImportable = 0;
        for (String s : sellable) {
            if (claims.containsKey(s)) {
                continue;
            }
            if (kind(s) != Kind.PRODUCT) {
                notImportable++;
                continue;
            }
            Set<String> holders = new TreeSet<>();
            for (String v : variantParts(s)) {
                for (StoreProduct c : claims.getOrDefault(v, List.of())) {
                    holders.add(c.handle());
                }
            }
            String name = names.getOrDefault(s, "");
            (holders.isEmpty() ? fresh : related).add(new SupplierEntry(display(s), name, List.copyOf(holders)));
        }
        return new Result(entries, fresh, related, notImportable);
    }

    private Entry entry(StoreProduct p, Map<String, List<StoreProduct>> claims,
                        Map<StoreProduct, Set<String>> variantsOf, Map<String, Set<String>> variantHolders,
                        Map<String, Set<String>> partHolders) {
        List<String> ids = p.supplierIds();
        boolean synced = p.lastSyncAt() != null && !p.lastSyncAt().isBlank();
        if (isArchived(p)) {
            String why = p.groupedInto() != null && !p.groupedInto().isBlank()
                    ? "Merged into " + p.groupedInto() : "Archived in the store";
            return entry(p, Category.ARCHIVED, why, false, null);
        }
        if (ids.isEmpty()) {
            return entry(p, Category.NEEDS_FIX, "Lists no PaceSetter code", false, null);
        }
        Map<String, Kind> kinds = new LinkedHashMap<>();
        for (String id : ids) {
            kinds.put(id, kind(id));
        }
        List<String> live = ids.stream().filter(id -> kinds.get(id) == Kind.PRODUCT).toList();
        List<String> dead = ids.stream().filter(id -> kinds.get(id) != Kind.PRODUCT).toList();

        if (synced && !live.isEmpty()) {
            String why = dead.isEmpty() ? "Synced" : "Synced; " + others(kinds, dead);
            return entry(p, Category.SYNCED, why, false, null);
        }
        if (synced) {
            return entry(p, Category.SYNCED_NO_DATA,
                    "Synced once, but now PaceSetter " + describeMissing(kinds) + "; it is no longer updated",
                    false, null);
        }
        if (!live.isEmpty()) {
            String blocked = sharedWith(p, claims, variantsOf, variantHolders);
            String why = "PaceSetter has everything; never synced"
                    + (dead.isEmpty() ? "" : ". Not synced with it: " + others(kinds, dead));
            return entry(p, Category.READY, why, blocked == null, blocked);
        }

        List<String> partIds = ids.stream().filter(id -> kinds.get(id) == Kind.PART).toList();
        if (!partIds.isEmpty()) {
            Set<String> parents = new TreeSet<>();
            partIds.forEach(id -> parents.addAll(owners(key(id))));
            List<String> missing = parents.stream().filter(o -> kind(o) != Kind.PRODUCT).toList();
            // Something to do as soon as one parent is complete: the rest waits on PaceSetter, and
            // the reason says which (the beverage holders list CM727* colours and CM737* ones).
            if (missing.size() < parents.size()) {
                // Store products listing its colours, and any listing the product code itself.
                Set<String> holders = new TreeSet<>();
                for (String o : parents) {
                    holders.addAll(partHolders.getOrDefault(o, Set.of()));
                    claims.getOrDefault(o, List.of()).stream().filter(c -> !isArchived(c))
                            .forEach(c -> holders.add(c.handle()));
                }
                holders.remove(p.handle());
                Set<String> ready = new TreeSet<>(parents);
                missing.forEach(ready::remove);
                String why = "Its codes are variants of " + joinDisplay(ready)
                        + (holders.isEmpty() ? "; list that code instead"
                        : ", split across " + (holders.size() + 1) + " store products — merge them")
                        + (missing.isEmpty() ? "" : ". Those of " + String.join(", ", missing.stream().map(this::display).toList())
                        + " wait: PaceSetter has " + missingWhat(missing));
                return entry(p, Category.NEEDS_FIX, why, false, null);
            }
            return entry(p, Category.WAITING, "Its codes are variants of " + joinDisplay(parents)
                    + ", for which PaceSetter has " + missingWhat(missing), false, null);
        }
        if (kinds.containsValue(Kind.NO_PRICE) || kinds.containsValue(Kind.NO_PRODUCT_DATA)) {
            return entry(p, Category.WAITING, "PaceSetter " + describeMissing(kinds), false, null);
        }
        // Only the family's own stem is a code fix (CM713 for CM713*). A longer code under the same
        // prefix (CM295CK under CM295*) is in no answer of that family: most likely gone.
        List<String> stems = ids.stream().filter(id -> kinds.get(id) == Kind.FAMILY_PART
                && key(id).equals(familyOf(key(id)))).toList();
        if (!stems.isEmpty()) {
            String id = stems.get(0);
            return entry(p, Category.NEEDS_FIX, id + " is not a PaceSetter code; it sells "
                    + familyOf(key(id)) + "* — list that instead", false, null);
        }
        List<String> underFamily = ids.stream().filter(id -> kinds.get(id) == Kind.FAMILY_PART).toList();
        return entry(p, Category.DISCONTINUED, "PaceSetter no longer has any of its codes"
                + (underFamily.isEmpty() ? "" : " (" + join(underFamily) + " falls under "
                + familyOf(key(underFamily.get(0))) + "*, but no answer of that family lists it)"), false, null);
    }

    /**
     * Why a never-synced product must not be synced from the console: a sync takes in every variant
     * PaceSetter answers for its codes, so it would duplicate one another store product already sells
     * (Polar Camel Stemless lists CM712, whose colours "Polar Camel Medium" sells one by one).
     */
    private String sharedWith(StoreProduct p, Map<String, List<StoreProduct>> claims,
                              Map<StoreProduct, Set<String>> variantsOf, Map<String, Set<String>> variantHolders) {
        Map<String, String> others = new LinkedHashMap<>();
        for (String v : variantsOf.getOrDefault(p, Set.of())) {
            for (StoreProduct c : claims.getOrDefault(v, List.of())) {
                if (c != p && !isArchived(c)) {
                    others.putIfAbsent(c.handle(), v);
                }
            }
            for (String h : variantHolders.getOrDefault(v, Set.of())) {
                if (!h.equals(p.handle())) {
                    others.putIfAbsent(h, v);
                }
            }
        }
        if (others.isEmpty()) {
            return null;
        }
        return "A sync would take in variants another store product sells: " + others.entrySet().stream()
                .limit(3).map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "))
                + (others.size() > 3 ? " and " + (others.size() - 3) + " more" : "") + " — merge first";
    }

    Kind kind(String id) {
        String k = key(id);
        boolean readable = !nonNull(productParts.get(k)).isEmpty();
        boolean priced = !nonNull(pricedParts.get(k)).isEmpty();
        if (sellable.contains(k) && readable && priced) {
            return Kind.PRODUCT;
        }
        if (sellable.contains(k) && readable) {
            return Kind.NO_PRICE;
        }
        if (sellable.contains(k)) {
            return Kind.NO_PRODUCT_DATA;
        }
        if (declaredOwners.containsKey(k) || inventoryOwners.containsKey(k)) {
            return Kind.PART;
        }
        if (familyOf(k) != null) {
            return Kind.FAMILY_PART;
        }
        return Kind.ORPHAN;
    }

    /** The product that declares a part as its own; an Inventory answer only when none does. */
    private Set<String> owners(String part) {
        Set<String> declared = declaredOwners.get(part);
        return declared != null && !declared.isEmpty() ? declared : inventoryOwners.getOrDefault(part, Set.of());
    }

    /** The code itself plus every part its product data, price table and Inventory answer list. */
    private Set<String> variantParts(String id) {
        Set<String> parts = new TreeSet<>();
        parts.add(id);
        parts.addAll(nonNull(productParts.get(id)));
        parts.addAll(nonNull(pricedParts.get(id)));
        parts.addAll(nonNull(inventoryParts.get(id)));
        return parts;
    }

    private String familyOf(String id) {
        return families.stream().filter(id::startsWith).findFirst().orElse(null);
    }

    /**
     * What a sync leaves out of a product it can run on, by why: codes that are another product's
     * variants (Freestanding Lucite Block lists CD549B, a size of CD549), codes PaceSetter has no data
     * or price for, and codes it no longer has at all. "No longer has" for all of them read as if the
     * blocks had been discontinued.
     */
    private String others(Map<String, Kind> kinds, List<String> dead) {
        List<String> out = new ArrayList<>();
        List<String> parts = dead.stream().filter(id -> kinds.get(id) == Kind.PART).toList();
        if (!parts.isEmpty()) {
            Set<String> owners = new TreeSet<>();
            parts.forEach(id -> owners.addAll(owners(key(id))));
            out.add(join(parts) + " (variants of " + joinDisplay(owners) + ")");
        }
        List<String> incomplete = dead.stream()
                .filter(id -> kinds.get(id) == Kind.NO_PRICE || kinds.get(id) == Kind.NO_PRODUCT_DATA).toList();
        if (!incomplete.isEmpty()) {
            out.add(join(incomplete) + " (no product data or no price at PaceSetter)");
        }
        List<String> gone = dead.stream()
                .filter(id -> kinds.get(id) == Kind.ORPHAN || kinds.get(id) == Kind.FAMILY_PART).toList();
        if (!gone.isEmpty()) {
            out.add(join(gone) + " (PaceSetter no longer has " + (gone.size() == 1 ? "it" : "them") + ")");
        }
        return String.join("; ", out);
    }

    private String describeMissing(Map<String, Kind> kinds) {
        List<String> parts = new ArrayList<>();
        List<String> noPrice = idsOf(kinds, Kind.NO_PRICE);
        List<String> noData = idsOf(kinds, Kind.NO_PRODUCT_DATA);
        if (!noPrice.isEmpty()) {
            parts.add("has no price for " + join(noPrice));
        }
        if (!noData.isEmpty()) {
            parts.add("has no product data for " + join(noData));
        }
        List<String> gone = kinds.entrySet().stream()
                .filter(e -> e.getValue() != Kind.NO_PRICE && e.getValue() != Kind.NO_PRODUCT_DATA)
                .map(Map.Entry::getKey).toList();
        if (!gone.isEmpty()) {
            parts.add("no longer lists " + join(gone));
        }
        return String.join(" and ", parts);
    }

    private String missingWhat(List<String> missing) {
        List<String> noPrice = missing.stream().filter(o -> kind(o) == Kind.NO_PRICE).map(this::display).toList();
        List<String> noData = missing.stream().filter(o -> kind(o) != Kind.NO_PRICE).map(this::display).toList();
        List<String> parts = new ArrayList<>();
        if (!noData.isEmpty()) {
            parts.add("no product data (" + String.join(", ", noData) + ")");
        }
        if (!noPrice.isEmpty()) {
            parts.add("no price (" + String.join(", ", noPrice) + ")");
        }
        return String.join(" and ", parts);
    }

    private static List<String> idsOf(Map<String, Kind> kinds, Kind kind) {
        return kinds.entrySet().stream().filter(e -> e.getValue() == kind).map(Map.Entry::getKey).toList();
    }

    private static Entry entry(StoreProduct p, Category category, String reason, boolean syncable,
                               String syncBlockedReason) {
        String adminId = p.gid() == null ? null : p.gid().substring(p.gid().lastIndexOf('/') + 1);
        boolean tagged = p.tags() != null && p.tags().stream().anyMatch(DISCONTINUED_TAG::equalsIgnoreCase);
        return new Entry(category, p.handle(), p.title(), p.status(), adminId, p.imageUrl(),
                List.copyOf(p.supplierIds()), reason, syncable, syncBlockedReason, tagged, p.lastSyncAt());
    }

    private static boolean isArchived(StoreProduct p) {
        return "ARCHIVED".equalsIgnoreCase(p.status());
    }

    /** As PaceSetter spells it, for display; lookups are case-insensitive. */
    private String display(String id) {
        return supplier.sellable().stream().filter(s -> key(s).equals(id)).findFirst().orElse(id);
    }

    private String joinDisplay(Set<String> ids) {
        return ids.stream().map(this::display).collect(Collectors.joining(", "));
    }

    private static String join(List<String> ids) {
        return ids.size() <= 4 ? String.join(", ", ids)
                : String.join(", ", ids.subList(0, 4)) + " and " + (ids.size() - 4) + " more";
    }

    private static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }

    static String key(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, List<String>> upperKeys(Map<String, List<String>> in) {
        Map<String, List<String>> out = new HashMap<>();
        if (in != null) {
            in.forEach((id, parts) -> out.put(key(id),
                    nonNull(parts).stream().map(CatalogStatusClassifier::key).filter(s -> !s.isEmpty()).toList()));
        }
        return out;
    }

    private static List<String> nonNull(List<String> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new ArrayList<>(nonNull(a));
        out.addAll(nonNull(b));
        return out;
    }
}
