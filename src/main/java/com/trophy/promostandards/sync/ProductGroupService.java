package com.trophy.promostandards.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.sync.ShopifySyncService.ImportedProduct;
import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.model.ProductGroupPreview;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.trophy.promostandards.sync.ShopifySyncService.checkUserErrors;
import static com.trophy.promostandards.sync.ShopifySyncService.require;

/**
 * Selling several of the supplier's products as one product of the store.
 *
 * <p>PaceSetter splits what a shop sells as one article across ids — a keychain per colour, a plaque
 * per size — and the store's own catalogue did the same, which is why 240 products sit waiting for
 * someone to say which of them is the real one. The store already models the answer
 * ({@code custom.ps_product_ids}), and the sync already unions it; this decides it from the app
 * rather than a spreadsheet, and writes the decision into the store, where it stays readable.
 *
 * <p>The preview comes first and on its own, because applying cannot be undone by writing the
 * metafields back: the sync never deletes a variant, so a group made by mistake leaves its variants
 * behind on the parent. Applying runs the very same checks again, against a fresh listing of the
 * store, and refuses whatever the preview would refuse.
 */
@Service
public class ProductGroupService {

    private static final Logger log = LoggerFactory.getLogger(ProductGroupService.class);

    /** {@code PRODUCT_BY_HANDLE} reads this many variants of a product; the sync cannot see past it. */
    static final int MAX_VARIANTS = 100;

    /** Shopify's ceiling on one {@code metafieldsSet}; deletes are batched the same way. */
    private static final int METAFIELDS_PER_CALL = 25;

    /** Left on an absorbed product: the handle of the product that took its ids, and those ids. */
    static final String MF_GROUPED_INTO = "grouped_into";
    static final String MF_GROUPED_IDS = "grouped_ids";

    private final ShopifySyncService sync;
    private final ShopifyGraphQLClient gql;
    private final ObjectMapper objectMapper;

    /** Two groupings at once could each validate against a store the other is about to change. */
    private final Object applyLock = new Object();

    public ProductGroupService(ShopifySyncService sync, ShopifyGraphQLClient gql, ObjectMapper objectMapper) {
        this.sync = sync;
        this.gql = gql;
        this.objectMapper = objectMapper;
    }

    /**
     * @param parentProductId  the supplier id whose store product would keep its identity and carry
     *                         all the variants
     * @param memberProductIds the supplier ids to sell under it
     */
    public ProductGroupPreview preview(String parentProductId, List<String> memberProductIds) {
        return plan(parentProductId, memberProductIds).preview();
    }

    /**
     * What applying a grouping did.
     *
     * @param supplierIds what the parent's {@code custom.ps_product_ids} now lists
     * @param archived    handles of the store products whose ids moved to the parent
     * @param sync        the parent's sync, which is what creates the grouped variants — null when it
     *                    failed, in which case {@code syncError} says why and the grouping stands:
     *                    syncing the parent again (by hand or on schedule) finishes it
     */
    public record Applied(String parentProductId, String parentHandle, List<String> supplierIds,
                          List<String> archived, SyncResult sync, String syncError) {
    }

    /**
     * Writes the grouping into the store and syncs the parent so it carries the variants.
     *
     * <p>The order is what makes a failure part-way harmless. Nothing is removed until the last
     * write, so every earlier failure leaves a state that applying the same grouping again completes:
     * <ol>
     *   <li>the parent's new {@code ps_product_ids}, and on each absorbed product a note of where its
     *       ids went ({@code trophy_sync.grouped_into} / {@code grouped_ids});</li>
     *   <li>the absorbed products are archived — not deleted: their order history stays, and the
     *       admin can bring one back;</li>
     *   <li>their {@code ps_product_id} / {@code ps_product_ids} are deleted, which is what stops two
     *       products claiming one id;</li>
     *   <li>the parent is synced, which adopts, creates and prices the grouped variants.</li>
     * </ol>
     *
     * @throws GroupConflictException when the preview refuses the grouping; nothing is written
     */
    public Applied apply(String parentProductId, List<String> memberProductIds) {
        synchronized (applyLock) {
            Plan plan = plan(parentProductId, memberProductIds);
            ProductGroupPreview preview = plan.preview();
            if (!preview.applicable()) {
                throw new GroupConflictException(preview.conflicts());
            }
            String parentHandle = plan.parent().handle();

            step(parentHandle, "recording the grouping", () -> setMetafields(recordMetafields(plan)));
            for (ImportedProduct product : plan.absorbed()) {
                step(parentHandle, "archiving " + product.handle(), () -> archive(product.gid()));
            }
            step(parentHandle, "removing the moved ids", () -> deleteIdentity(plan.absorbed()));
            sync.invalidateImportedIndex();
            List<String> archived = plan.absorbed().stream().map(ImportedProduct::handle).toList();
            log.info("Grouped {} under {} ({}); archived {}", plan.supplierIds(), parentHandle,
                    preview.parentProductId(), archived);

            SyncResult result = null;
            String syncError = null;
            try {
                result = sync.importProduct(preview.parentProductId());
            } catch (RuntimeException e) {
                // The grouping is written either way. Reporting instead of throwing keeps that fact in
                // the answer: a 502 here would read as "nothing happened", and the next click would
                // try to group what is already grouped.
                syncError = e.getMessage();
                log.warn("Grouped under {} but its sync failed: {}", parentHandle, e.getMessage());
            }
            return new Applied(preview.parentProductId(), parentHandle, plan.supplierIds(), archived,
                    result, syncError);
        }
    }

    // ---------------------------------------------------------------- validation

    /**
     * @param parent      the store product that keeps its identity, or null when none carries the id
     * @param absorbed    the store products the members move out of
     * @param supplierIds what the parent's {@code ps_product_ids} will list, its canonical id first
     */
    private record Plan(ProductGroupPreview preview, ImportedProduct parent, List<ImportedProduct> absorbed,
                        List<String> supplierIds) {
    }

    private Plan plan(String parentProductId, List<String> memberProductIds) {
        if (parentProductId == null || parentProductId.isBlank()) {
            throw new IllegalArgumentException("parentProductId is required");
        }
        String parentId = parentProductId.trim();
        Map<String, String> requested = new LinkedHashMap<>();
        if (memberProductIds != null) {
            for (String id : memberProductIds) {
                if (id != null && !id.isBlank() && !key(id).equals(key(parentId))) {
                    requested.putIfAbsent(key(id), id.trim());
                }
            }
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException(
                    "memberProductIds: name at least one supplier id to group under " + parentId);
        }
        List<String> members = List.copyOf(requested.values());

        List<ImportedProduct> store = sync.importedProductsOrEmpty();
        List<ImportedProduct> parentHolders = holders(store, parentId);
        ImportedProduct parent = parentHolders.stream()
                .filter(p -> parentId.equalsIgnoreCase(p.canonicalId())).findFirst()
                .orElse(parentHolders.isEmpty() ? null : parentHolders.get(0));

        // What the parent will list: its own ids first, so its canonical one stays first, then the
        // members. The migration's own check (ps_product_ids[0] == ps_product_id) keeps holding.
        Map<String, String> ids = new LinkedHashMap<>();
        if (parent != null) {
            parent.supplierIds().forEach(id -> ids.putIfAbsent(key(id), id));
        }
        ids.putIfAbsent(key(parentId), parentId);
        members.forEach(id -> ids.putIfAbsent(key(id), id));
        List<String> supplierIds = List.copyOf(ids.values());

        List<String> conflicts = new ArrayList<>();
        if (parent == null) {
            // Creating products stays off: grouping is for what the store already sells.
            conflicts.add("no store product carries " + parentId
                    + "; group under one the store already has");
        } else {
            if (parentHolders.size() > 1) {
                conflicts.add(parentId + " is carried by " + handles(parentHolders)
                        + "; the sync updates whichever it finds first, so one of them has to let go of it");
            }
            if (parent.handle() != null && parent.handle().equals(sync.appHandle(parentId))) {
                conflicts.add(parent.handle() + " was created by this app, and its sync replaces the "
                        + "variant list instead of adding the grouped ids' variants");
            }
        }

        // The store products the members move out of. Every product carrying a member, not just the
        // first one the index would find: after a grouping stopped part-way both the parent and the
        // old product list the id, and repeating the grouping has to find the old one again.
        Map<String, ImportedProduct> absorbedByGid = new LinkedHashMap<>();
        for (String id : members) {
            for (ImportedProduct holder : holders(store, id)) {
                if (parent == null || !holder.gid().equals(parent.gid())) {
                    absorbedByGid.putIfAbsent(holder.gid(), holder);
                }
            }
        }
        // An id the caller did not ask for would be stranded: its product is about to lose its
        // identity metafields, so it would stop being synced by anything. Say so rather than silently
        // take it along.
        List<ProductGroupPreview.Absorbed> absorbed = new ArrayList<>();
        for (ImportedProduct holder : absorbedByGid.values()) {
            List<String> notRequested = holder.supplierIds().stream()
                    .filter(other -> !ids.containsKey(key(other))).toList();
            absorbed.add(new ProductGroupPreview.Absorbed(holder.handle(), List.copyOf(holder.supplierIds()),
                    notRequested));
            if (!notRequested.isEmpty()) {
                conflicts.add(holder.handle() + " also carries " + notRequested
                        + "; include them or they are left behind");
            }
        }
        List<ImportedProduct> absorbedProducts = List.copyOf(absorbedByGid.values());

        if (parent == null) {
            return new Plan(new ProductGroupPreview(parentId, null, supplierIds, List.of(), absorbed,
                    conflicts, List.of(), false), null, absorbedProducts, supplierIds);
        }

        // The parent must be a product the sync adds variants to. Otherwise the absorbed products
        // would be archived with nowhere for their variants to go.
        JsonNode storeProduct = sync.findByHandle(parent.handle());
        if (storeProduct == null) {
            conflicts.add(parent.handle() + " could not be read from the store");
        } else {
            String shape = ForeignProductSync.unmodelledShape(storeProduct);
            if (shape != null) {
                conflicts.add(parent.handle() + " " + shape + "; the sync would not add the grouped "
                        + "variants to it");
            }
        }

        ForeignProductSync.GroupPreview union = sync.groupPreview(parentId, supplierIds);
        List<Variant> variants = union.product().variants();
        List<ProductGroupPreview.Variant> rows = new ArrayList<>(variants.size());
        for (int i = 0; i < variants.size(); i++) {
            Variant v = variants.get(i);
            rows.add(new ProductGroupPreview.Variant(union.supplierIds().get(i), v.supplierPartId(),
                    union.colorLabels().get(i), union.emitSize() ? v.size() : null, v.onHand()));
        }
        if (rows.size() > MAX_VARIANTS) {
            conflicts.add(parent.handle() + " would carry " + rows.size() + " variants; the sync reads "
                    + MAX_VARIANTS + " per product and would try to create the rest again on every run");
        }

        List<String> warnings = new ArrayList<>(union.product().warnings());
        Set<String> standing = union.supplierIds().stream().map(ProductGroupService::key)
                .collect(Collectors.toSet());
        for (String id : supplierIds) {
            if (!standing.contains(key(id))) {
                warnings.add(id + ": no variant stands for it — the supplier does not answer for it as a product");
            }
        }

        return new Plan(new ProductGroupPreview(parentId, parent.handle(), supplierIds, rows, absorbed,
                conflicts, warnings, conflicts.isEmpty()), parent, absorbedProducts, supplierIds);
    }

    /** Every store product carrying {@code id}, in listing order. */
    private static List<ImportedProduct> holders(List<ImportedProduct> store, String id) {
        return store.stream()
                .filter(p -> p.supplierIds().stream().anyMatch(other -> key(other).equals(key(id))))
                .toList();
    }

    private static String handles(List<ImportedProduct> products) {
        return products.stream().map(ImportedProduct::handle).collect(Collectors.joining(" and "));
    }

    // ---------------------------------------------------------------- writes

    /** Runs one write, naming what stopped and why repeating the grouping is safe when it fails. */
    private static void step(String parentHandle, String what, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            throw new ShopifySyncException("Grouping under " + parentHandle + " stopped while " + what
                    + ": " + e.getMessage() + ". Whatever was written so far belongs to this same "
                    + "grouping, and applying it again finishes it.");
        }
    }

    /** The parent's new list, and the note left on each absorbed product. Adds; removes nothing. */
    private List<Map<String, Object>> recordMetafields(Plan plan) {
        List<Map<String, Object>> metafields = new ArrayList<>();
        metafields.add(metafield(plan.parent().gid(), ShopifyProductMapper.METAFIELD_NAMESPACE,
                ShopifyProductMapper.MF_PRODUCT_IDS, "list.single_line_text_field", json(plan.supplierIds())));
        for (ImportedProduct product : plan.absorbed()) {
            metafields.add(metafield(product.gid(), ShopifyProductMapper.SYNC_NAMESPACE, MF_GROUPED_INTO,
                    "single_line_text_field", plan.parent().handle()));
            metafields.add(metafield(product.gid(), ShopifyProductMapper.SYNC_NAMESPACE, MF_GROUPED_IDS,
                    "list.single_line_text_field", json(product.supplierIds())));
        }
        return metafields;
    }

    private void setMetafields(List<Map<String, Object>> metafields) {
        for (List<Map<String, Object>> batch : batches(metafields)) {
            JsonNode data = gql.execute(ShopifyGraphQL.METAFIELDS_SET, Map.of("metafields", batch));
            checkUserErrors(require(data).path("metafieldsSet"), "metafieldsSet");
        }
    }

    private void archive(String productGid) {
        JsonNode data = gql.execute(ShopifyGraphQL.PRODUCT_ARCHIVE, Map.of("id", productGid));
        checkUserErrors(require(data).path("productUpdate"), "productUpdate(status: ARCHIVED)");
    }

    /** Deleting a metafield a product does not have is not an error, so this is safe to repeat. */
    private void deleteIdentity(List<ImportedProduct> products) {
        List<Map<String, Object>> identifiers = new ArrayList<>();
        for (ImportedProduct product : products) {
            for (String key : List.of(ShopifyProductMapper.MF_PRODUCT_ID, ShopifyProductMapper.MF_PRODUCT_IDS)) {
                identifiers.add(Map.of("ownerId", product.gid(),
                        "namespace", ShopifyProductMapper.METAFIELD_NAMESPACE, "key", key));
            }
        }
        for (List<Map<String, Object>> batch : batches(identifiers)) {
            JsonNode data = gql.execute(ShopifyGraphQL.METAFIELDS_DELETE, Map.of("metafields", batch));
            checkUserErrors(require(data).path("metafieldsDelete"), "metafieldsDelete");
        }
    }

    private static Map<String, Object> metafield(String ownerId, String namespace, String key, String type,
                                                 String value) {
        return Map.of("ownerId", ownerId, "namespace", namespace, "key", key, "type", type, "value", value);
    }

    private static <T> List<List<T>> batches(List<T> items) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < items.size(); i += METAFIELDS_PER_CALL) {
            batches.add(items.subList(i, Math.min(items.size(), i + METAFIELDS_PER_CALL)));
        }
        return batches;
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialise " + values, e);
        }
    }

    private static String key(String id) {
        return id == null ? "" : id.trim().toUpperCase(Locale.ROOT);
    }
}
