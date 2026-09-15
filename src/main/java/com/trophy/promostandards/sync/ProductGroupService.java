package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.ShopifySyncService.ImportedProduct;
import com.trophy.promostandards.sync.model.ProductGroupPreview;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Selling several of the supplier's products as one product of the store.
 *
 * <p>PaceSetter splits what a shop sells as one article across ids — a keychain per colour, a plaque
 * per size — and the store's own catalogue did the same, which is why 240 products sit waiting for
 * someone to say which of them is the real one. The store already models the answer
 * ({@code custom.ps_product_ids}), and the sync already unions it; what is missing is deciding it
 * from the app rather than a spreadsheet.
 *
 * <p>This half only answers. Applying it writes metafields on a live store and cannot be undone by
 * writing them back — the sync never deletes a variant, so a group made by mistake leaves its
 * variants behind — so the preview comes first and on its own.
 */
@Service
public class ProductGroupService {

    private final ShopifySyncService sync;

    public ProductGroupService(ShopifySyncService sync) {
        this.sync = sync;
    }

    /**
     * @param parentProductId  the supplier id whose store product would keep its identity and carry
     *                         all the variants
     * @param memberProductIds the supplier ids to sell under it
     */
    public ProductGroupPreview preview(String parentProductId, List<String> memberProductIds) {
        if (parentProductId == null || parentProductId.isBlank()) {
            throw new IllegalArgumentException("parentProductId is required");
        }
        List<String> members = memberProductIds == null ? List.of() : memberProductIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .filter(id -> !key(id).equals(key(parentProductId)))
                .toList();

        Map<String, ImportedProduct> byId = claims();
        ImportedProduct parent = byId.get(key(parentProductId));
        List<String> conflicts = new ArrayList<>();
        if (parent == null) {
            // Creating products stays off: grouping is for what the store already sells.
            conflicts.add("no store product carries " + parentProductId
                    + "; group under one the store already has");
        }

        // Which store products would be emptied, and what else they carry. An id the caller did not
        // ask for would be stranded: its product is about to lose its identity metafields, so it
        // would stop being synced by anything. Say so rather than silently take it along.
        Set<String> requested = new LinkedHashSet<>();
        requested.add(key(parentProductId));
        members.forEach(id -> requested.add(key(id)));
        Map<String, ProductGroupPreview.Absorbed> absorbed = new LinkedHashMap<>();
        for (String id : members) {
            ImportedProduct holder = byId.get(key(id));
            if (holder == null || (parent != null && holder.gid().equals(parent.gid()))) {
                continue;   // an id no store product carries, or one the parent already covers
            }
            absorbed.computeIfAbsent(holder.gid(), gid -> new ProductGroupPreview.Absorbed(
                    holder.handle(), List.copyOf(holder.supplierIds()),
                    holder.supplierIds().stream().filter(other -> !requested.contains(key(other)))
                            .toList()));
        }
        absorbed.values().stream().filter(a -> !a.notRequested().isEmpty()).forEach(a ->
                conflicts.add(a.handle() + " also carries " + a.notRequested()
                        + "; include them or they are left behind"));

        if (parent == null) {
            return new ProductGroupPreview(parentProductId, null, List.copyOf(requested), List.of(),
                    List.copyOf(absorbed.values()), conflicts, List.of(), false);
        }

        ForeignProductSync.GroupPreview union = sync.groupPreview(parentProductId, members);
        List<Variant> variants = union.product().variants();
        List<ProductGroupPreview.Variant> rows = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            Variant v = variants.get(i);
            rows.add(new ProductGroupPreview.Variant(union.supplierIds().get(i), v.supplierPartId(),
                    union.colorLabels().get(i), union.emitSize() ? v.size() : null, v.onHand()));
        }
        return new ProductGroupPreview(parentProductId, parent.handle(), List.copyOf(requested), rows,
                List.copyOf(absorbed.values()), conflicts, union.product().warnings(),
                conflicts.isEmpty());
    }

    /** Supplier id (upper-cased) -> the store product that carries it. */
    private Map<String, ImportedProduct> claims() {
        Map<String, ImportedProduct> byId = new LinkedHashMap<>();
        for (ImportedProduct product : sync.importedProductsOrEmpty()) {
            for (String id : product.supplierIds()) {
                byId.putIfAbsent(key(id), product);
            }
        }
        return byId;
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }
}
