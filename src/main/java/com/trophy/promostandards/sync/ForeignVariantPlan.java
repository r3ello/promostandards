package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SupplierProduct.Variant;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What to do with each supplier variant of a store product this app did not create — in practice a
 * product the one-shot trophypartner migration created, which carries the PromoStandards identity
 * metafields but a single legacy variant ({@code Title / Default Title}, SKU {@code PS11592}).
 *
 * <p>Matching is deliberately widest-first: the supplier part id in {@code trophy_sync.vendor_sku},
 * then the SKU, then the variant's {@code custom.promo_standard_id} (only when it identifies exactly
 * one store variant), then the option values. A migrated variant
 * matches on none of those, so a product left with exactly one unmatched variant has it
 * <b>adopted</b> — reused as the first supplier variant (keeping its id, and with it the order
 * history and any inventory) rather than deleted and recreated.
 */
record ForeignVariantPlan(List<Entry> entries, List<StoreVariant> orphans) {

    enum Action {
        /** Reuse the product's lone legacy variant: rewrite its SKU, price and option values. */
        ADOPT,
        /** A store variant already stands for this supplier variant: refresh price and identity. */
        UPDATE,
        /** No store variant stands for it yet: create it. */
        CREATE
    }

    /**
     * A variant as it exists in Shopify right now.
     *
     * @param vendorSku the supplier part id this variant stands for ({@code trophy_sync.vendor_sku}).
     *                  Since the SKU became the store's own number ({@code PS1298-LB}), this is the
     *                  only field that ties a store variant to a supplier part, and it is matched
     *                  first.
     */
    record StoreVariant(String id, String sku, String promoStandardId, String vendorSku, String color,
                        String size, String inventoryItemId, boolean tracked, Integer available) {
    }

    /**
     * One supplier variant and its fate.
     *
     * @param supplierId the supplier product id this variant stands for — the part id, which for
     *                   PaceSetter's grouped families ({@code CM297LB}) is itself a product id
     * @param target     the store variant to write to; null for {@link Action#CREATE}
     */
    record Entry(Variant variant, String supplierId, String colorLabel, String sizeLabel,
                 Action action, StoreVariant target) {
    }

    /**
     * @param supplier    the union of supplier variants the store product should end up with
     * @param colorLabels one Color option value per supplier variant (see {@link VariantOptions})
     * @param emitSize    whether variants carry a Size option value
     * @param store       the product's current variants
     */
    static ForeignVariantPlan of(List<Variant> supplier, List<String> colorLabels, boolean emitSize,
                                 List<StoreVariant> store) {
        return of(supplier, colorLabels, emitSize, store, Variant::supplierPartId);
    }

    /**
     * @param supplierIdOf the supplier product a variant stands for — its own part id when that is a
     *                     product id (CM297LB), else the product it is a part of (CM330BS → CM330);
     *                     null falls back to the part id
     */
    static ForeignVariantPlan of(List<Variant> supplier, List<String> colorLabels, boolean emitSize,
                                 List<StoreVariant> store,
                                 java.util.function.Function<Variant, String> supplierIdOf) {
        Map<String, StoreVariant> byVendorSku = index(store, StoreVariant::vendorSku);
        Map<String, StoreVariant> bySku = index(store, StoreVariant::sku);
        Map<String, StoreVariant> byPromoId = uniqueIndex(store, StoreVariant::promoStandardId);
        Map<String, StoreVariant> byOptions = new LinkedHashMap<>();
        for (StoreVariant v : store) {
            // Option values are already normalised by VariantOptions.key, not by upper().
            byOptions.putIfAbsent(optionKey(v.color(), v.size()), v);
        }

        Set<String> claimed = new HashSet<>();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < supplier.size(); i++) {
            Variant v = supplier.get(i);
            String colorLabel = colorLabels.get(i);
            String sizeLabel = emitSize ? VariantOptions.size(v.size()) : null;
            StoreVariant target = firstUnclaimed(claimed,
                    // The part id first: it is the supplier's own identity, and unlike the SKU it
                    // survives the store renumbering its variants.
                    byVendorSku.get(upper(v.supplierPartId())),
                    bySku.get(upper(v.sku())),
                    byPromoId.get(upper(v.supplierPartId())),
                    byOptions.get(optionKey(colorLabel, sizeLabel)));
            if (target != null) {
                claimed.add(target.id());
            }
            String supplierId = supplierIdOf.apply(v);
            entries.add(new Entry(v, supplierId != null ? supplierId : v.supplierPartId(), colorLabel, sizeLabel,
                    target == null ? Action.CREATE : Action.UPDATE, target));
        }

        List<StoreVariant> orphans = new ArrayList<>();
        for (StoreVariant v : store) {
            if (!claimed.contains(v.id())) {
                orphans.add(v);
            }
        }
        // The migrated product's lone variant: adopt it rather than leaving it stranded beside the
        // variants we are about to create. Only ever the single-variant case — with several
        // unmatched variants there is no way to tell which supplier variant each one meant.
        if (store.size() == 1 && orphans.size() == 1) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).action() == Action.CREATE) {
                    Entry e = entries.get(i);
                    entries.set(i, new Entry(e.variant(), e.supplierId(), e.colorLabel(), e.sizeLabel(),
                            Action.ADOPT, orphans.get(0)));
                    orphans.clear();
                    break;
                }
            }
        }
        return new ForeignVariantPlan(List.copyOf(entries), List.copyOf(orphans));
    }

    /** @return the entries Shopify must be told about with {@code productVariantsBulkCreate}. */
    List<Entry> toCreate() {
        return entries.stream().filter(e -> e.action() == Action.CREATE).toList();
    }

    /** @return the entries backed by an existing store variant (adopted or already matching). */
    List<Entry> toUpdate() {
        return entries.stream().filter(e -> e.action() != Action.CREATE).toList();
    }

    Entry adopted() {
        return entries.stream().filter(e -> e.action() == Action.ADOPT).findFirst().orElse(null);
    }

    /** Every supplier id covered by this plan, in variant order. */
    List<String> supplierIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Entry e : entries) {
            if (e.supplierId() != null && !e.supplierId().isBlank()) {
                ids.add(e.supplierId());
            }
        }
        return List.copyOf(ids);
    }

    private static String optionKey(String color, String size) {
        return VariantOptions.key(color) + "|" + VariantOptions.key(size);
    }

    private static StoreVariant firstUnclaimed(Set<String> claimed, StoreVariant... candidates) {
        for (StoreVariant candidate : candidates) {
            if (candidate != null && !claimed.contains(candidate.id())) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<String, StoreVariant> index(List<StoreVariant> store,
                                                   java.util.function.Function<StoreVariant, String> key) {
        Map<String, StoreVariant> index = new LinkedHashMap<>();
        for (StoreVariant v : store) {
            String k = upper(key.apply(v));
            if (k != null) {
                index.putIfAbsent(k, v);
            }
        }
        return index;
    }

    /** Like {@link #index} but drops keys claimed by more than one variant — those identify nothing. */
    private static Map<String, StoreVariant> uniqueIndex(List<StoreVariant> store,
                                                         java.util.function.Function<StoreVariant, String> key) {
        Map<String, StoreVariant> index = new LinkedHashMap<>();
        Set<String> duplicated = new HashSet<>();
        for (StoreVariant v : store) {
            String k = upper(key.apply(v));
            if (k == null) {
                continue;
            }
            if (index.putIfAbsent(k, v) != null) {
                duplicated.add(k);
            }
        }
        index.keySet().removeAll(duplicated);
        return index;
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}
