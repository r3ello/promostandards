package com.trophy.promostandards.sync;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.sync.ShopifySyncService.ImportedProduct;
import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.model.ProductUngroupPreview;
import com.trophy.promostandards.sync.model.ProductUngroupPreview.Action;
import com.trophy.promostandards.sync.model.ProductUngroupPreview.Split;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.trophy.promostandards.sync.ShopifySyncService.checkUserErrors;
import static com.trophy.promostandards.sync.ShopifySyncService.require;

/**
 * Selling the supplier ids of one grouped store product as separate products again — the reverse of
 * {@link ProductGroupService}.
 *
 * <p>Done by hand twice on 2026-10-23 (the CD900* and CD953* state cutouts,
 * {@code reports/ungroup-*-2026-10-23/}); this is that procedure. A grouping archived the products
 * whose ids it took and left a note on each ({@code trophy_sync.grouped_into} = the grouped product's
 * handle, {@code grouped_ids} = the ids it had), which is what finds them again. An id no archived
 * product remembers — a product the migration already sold grouped — gets a product of its own only
 * when creating products is on.
 *
 * <p>Every product the split leaves is marked {@code trophy_sync.own_parts_only}: PaceSetter answers
 * Inventory for a whole family, and without the mark the next sync of any of them takes the family
 * back in — which is what the first attempt on CD900* did.
 */
@Service
public class ProductUngroupService {

    private static final Logger log = LoggerFactory.getLogger(ProductUngroupService.class);

    private static final int METAFIELDS_PER_CALL = 25;

    static final String MF_OWN_PARTS_ONLY = "own_parts_only";
    static final String MF_KEEP_STORE_IMAGES = "keep_store_images";

    /** What a product brought back from the archive is published as. */
    public static final Set<String> STATUSES = Set.of("ACTIVE", "DRAFT");

    private final ShopifySyncService sync;
    private final ShopifyGraphQLClient gql;
    private final ObjectMapper objectMapper;

    /** Two splits at once could each validate against a store the other is about to change. */
    private final Object applyLock = new Object();

    public ProductUngroupService(ShopifySyncService sync, ShopifyGraphQLClient gql, ObjectMapper objectMapper) {
        this.sync = sync;
        this.gql = gql;
        this.objectMapper = objectMapper;
    }

    /**
     * @param productId any supplier id the grouped product carries
     * @param splitIds  the ids to sell on their own; empty means every id but the product's own
     */
    public ProductUngroupPreview preview(String productId, List<String> splitIds) {
        return plan(productId, splitIds).preview();
    }

    /**
     * What applying a split did.
     *
     * @param restored the handles of the archived products brought back
     * @param toSync   the split ids, none synced yet: each is synced on its own afterwards (with
     *                 {@link #syncMetafields}, so one that has to be created is marked too) — a split
     *                 of 50 states is 50 syncs, far longer than one request should wait for
     * @param sync     the grouped product's own sync, which takes its options back to what is left;
     *                 null when it failed, in which case {@code syncError} says why and syncing it
     *                 again finishes it
     */
    public record Applied(String productId, String handle, List<String> keptIds, List<String> removedVariants,
                          List<String> restored, List<String> toSync, SyncResult sync, String syncError) {
    }

    /**
     * Writes the split into the store and syncs the grouped product.
     *
     * <p>In an order where stopping part-way is finished by applying the same split again, and where
     * no supplier id is ever on two products at once:
     * <ol>
     *   <li>the grouped product's variants of the split ids are deleted;</li>
     *   <li>its {@code ps_product_ids} lose them, and every product involved is marked
     *       {@code own_parts_only} — the restored ones get their ids back in the same call;</li>
     *   <li>the restored products take the requested status;</li>
     *   <li>their {@code grouped_into} / {@code grouped_ids} notes are deleted — last, because they
     *       are what a repeat finds them by;</li>
     *   <li>the grouped product is synced.</li>
     * </ol>
     *
     * @param status          ACTIVE or DRAFT, for the restored products
     * @param keepStoreImages also mark the split products {@code keep_store_images}, for families the
     *                        supplier serves one photo for (CD953*: Arizona for every state)
     * @throws GroupConflictException when the preview refuses the split; nothing is written
     */
    public Applied apply(String productId, List<String> splitIds, String status, boolean keepStoreImages) {
        String targetStatus = status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(targetStatus)) {
            throw new IllegalArgumentException("status must be ACTIVE or DRAFT, not " + status);
        }
        synchronized (applyLock) {
            Plan plan = plan(productId, splitIds);
            ProductUngroupPreview preview = plan.preview();
            if (!preview.applicable()) {
                throw new GroupConflictException("Split", preview.conflicts());
            }
            String handle = plan.parent().handle();

            if (!plan.removedVariantIds().isEmpty()) {
                step(handle, "deleting the split ids' variants", () -> deleteVariants(plan));
            }
            step(handle, "moving the ids", () -> setMetafields(identityMetafields(plan, keepStoreImages)));
            for (Restored r : plan.restored()) {
                if (!targetStatus.equals(r.status())) {
                    step(handle, "restoring " + r.handle(), () -> setStatus(r.gid(), targetStatus));
                }
            }
            step(handle, "removing the grouping notes", () -> deleteNotes(plan.restored()));
            sync.invalidateImportedIndex();
            List<String> restored = plan.restored().stream().map(Restored::handle).toList();
            log.info("Split {} off {} ({}); restored {}", plan.splitIds(), handle, preview.productId(), restored);

            SyncResult result = null;
            String syncError = null;
            try {
                result = sync.importProduct(preview.productId());
            } catch (RuntimeException e) {
                // The split is written either way: a 502 would read as "nothing happened".
                syncError = e.getMessage();
                log.warn("Split {} but its sync failed: {}", handle, e.getMessage());
            }
            return new Applied(preview.productId(), handle, preview.keptIds(), preview.removedVariants(),
                    restored, plan.splitIds(), result, syncError);
        }
    }

    /**
     * The metafields to sync a split id with. On a product that exists they are already written and
     * the sync ignores them; on one the sync creates, they are stamped at creation, before the first
     * union — so it never takes the family in, not even once.
     */
    public static List<SyncProperties.Metafield> syncMetafields(boolean keepStoreImages) {
        List<SyncProperties.Metafield> metafields = new ArrayList<>();
        metafields.add(new SyncProperties.Metafield(ShopifyProductMapper.SYNC_NAMESPACE, MF_OWN_PARTS_ONLY,
                "boolean", "true", null));
        if (keepStoreImages) {
            metafields.add(new SyncProperties.Metafield(ShopifyProductMapper.SYNC_NAMESPACE,
                    MF_KEEP_STORE_IMAGES, "boolean", "true", null));
        }
        return metafields;
    }

    // ---------------------------------------------------------------- validation

    /** An archived (or, after a stop part-way, already restored) product a grouping absorbed. */
    record Restored(String gid, String handle, String status, List<String> supplierIds) {
    }

    private record Plan(ProductUngroupPreview preview, ImportedProduct parent, List<String> splitIds,
                        List<String> keptIds, List<String> removedVariantIds, List<Restored> restored) {
    }

    private Plan plan(String productId, List<String> requestedIds) {
        if (productId == null || productId.isBlank()) {
            throw new IllegalArgumentException("productId is required");
        }
        String id = productId.trim();
        List<ImportedProduct> store = sync.importedProductsOrEmpty();
        List<ImportedProduct> holders = holders(store, id);
        List<String> conflicts = new ArrayList<>();
        if (holders.isEmpty()) {
            conflicts.add("no store product carries " + id);
            return refused(id, conflicts);
        }
        if (holders.size() > 1) {
            conflicts.add(id + " is carried by " + handles(holders) + "; one of them has to let go of it first");
            return refused(id, conflicts);
        }
        ImportedProduct parent = holders.get(0);
        String own = parent.canonicalId() != null && !parent.canonicalId().isBlank()
                ? parent.canonicalId() : parent.supplierIds().get(0);
        if (parent.handle() != null && parent.handle().equals(sync.appHandle(own))) {
            conflicts.add(parent.handle() + " was created by this app under its own handle; its sync "
                    + "replaces the variant list, so there is no grouping to undo");
        }

        // The products this grouping absorbed: archived, or restored already by a split that stopped
        // part-way — both still carry the note naming this product.
        List<Restored> absorbed = absorbedInto(parent.handle());

        Map<String, String> asked = new LinkedHashMap<>();
        if (requestedIds == null || requestedIds.stream().allMatch(x -> x == null || x.isBlank())) {
            parent.supplierIds().stream().filter(x -> !key(x).equals(key(own)))
                    .forEach(x -> asked.putIfAbsent(key(x), x));
        } else {
            requestedIds.stream().filter(x -> x != null && !x.isBlank())
                    .forEach(x -> asked.putIfAbsent(key(x), x.trim()));
        }
        if (asked.isEmpty()) {
            conflicts.add(parent.handle() + " carries only " + own + "; there is nothing to split off");
        }
        if (asked.containsKey(key(own))) {
            conflicts.add(own + " is " + parent.handle() + "'s own id and stays on it");
            asked.remove(key(own));
        }

        // Which product each split id goes to. A product that carried several ids takes all of them.
        Map<String, Restored> restoredByGid = new LinkedHashMap<>();
        List<String> created = new ArrayList<>();
        for (String splitId : asked.values()) {
            List<Restored> mine = absorbed.stream().filter(r -> containsId(r.supplierIds(), splitId)).toList();
            boolean onParent = containsId(parent.supplierIds(), splitId);
            List<ImportedProduct> elsewhere = holders(store, splitId).stream()
                    .filter(p -> !p.gid().equals(parent.gid()))
                    .filter(p -> mine.stream().noneMatch(r -> r.gid().equals(p.gid())))
                    .toList();
            if (!elsewhere.isEmpty()) {
                conflicts.add(splitId + " is already carried by " + handles(elsewhere));
            } else if (mine.size() > 1) {
                conflicts.add(splitId + " was grouped from " + mine.stream().map(Restored::handle)
                        .collect(Collectors.joining(" and ")) + "; only one of them can take it back");
            } else if (mine.size() == 1) {
                restoredByGid.putIfAbsent(mine.get(0).gid(), mine.get(0));
            } else if (!onParent) {
                conflicts.add(splitId + " is not on " + parent.handle());
            } else if (!sync.canCreateProducts()) {
                conflicts.add("no archived product remembers " + splitId + ", and creating products is off "
                        + "(sync.create-products.enabled); bring one back, or create it, first");
            } else {
                created.add(splitId);
            }
        }
        // An id a restored product had but nobody asked to split: it would be on two products.
        for (Restored r : restoredByGid.values()) {
            List<String> notAsked = r.supplierIds().stream().filter(x -> !asked.containsKey(key(x))).toList();
            if (!notAsked.isEmpty()) {
                conflicts.add(r.handle() + " had " + notAsked + " too; split them as well, or it comes back without them");
            }
        }

        List<String> splitIds = List.copyOf(asked.values());
        List<String> kept = parent.supplierIds().stream().filter(x -> !asked.containsKey(key(x))).toList();

        List<String> removedIds = new ArrayList<>();
        List<String> removedSkus = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode node = sync.findByHandle(parent.handle());
        if (node == null) {
            conflicts.add(parent.handle() + " could not be read from the store");
        } else {
            int unattributed = 0;
            JsonNode variants = node.path("variants").path("nodes");
            for (JsonNode v : variants) {
                String psId = v.path("psId").path("value").asText(null);
                if (psId == null || psId.isBlank()) {
                    unattributed++;
                } else if (asked.containsKey(key(psId))) {
                    removedIds.add(v.path("id").asText());
                    removedSkus.add(v.path("sku").asText(v.path("title").asText("")));
                }
            }
            if (!removedIds.isEmpty() && removedIds.size() == variants.size()) {
                conflicts.add("every variant of " + parent.handle() + " belongs to an id being split off; "
                        + "keep at least the variants of " + own);
            }
            if (unattributed > 0 && parent.supplierIds().size() > 1) {
                warnings.add(unattributed + " variant(s) of " + parent.handle() + " carry no "
                        + "custom.promo_standard_id, so nothing says which id they are; they stay on it");
            }
            if (!removedIds.isEmpty()) {
                warnings.add(removedIds.size() + " variant(s) are deleted from " + parent.handle()
                        + "; orders that bought one keep their lines, which no longer link to a variant");
            }
        }
        if (!created.isEmpty()) {
            warnings.add("No archived product remembers " + created + ": the sync creates one for each, "
                    + "as a draft");
        }

        List<Split> split = new ArrayList<>();
        restoredByGid.values().forEach(r -> split.add(new Split(r.supplierIds().get(0), Action.RESTORE,
                r.handle(), r.status(), r.supplierIds())));
        created.forEach(c -> split.add(new Split(c, Action.CREATE, null, null, List.of(c))));

        ProductUngroupPreview preview = new ProductUngroupPreview(own, parent.handle(), kept, split,
                removedSkus, conflicts, warnings, conflicts.isEmpty());
        return new Plan(preview, parent, splitIds, kept, removedIds, List.copyOf(restoredByGid.values()));
    }

    private static Plan refused(String id, List<String> conflicts) {
        return new Plan(new ProductUngroupPreview(id, null, List.of(), List.of(), List.of(), conflicts,
                List.of(), false), null, List.of(), List.of(), List.of(), List.of());
    }

    /**
     * Every PromoStandards product noted as grouped into {@code handle}. A full listing of the
     * store: nothing searches by metafield value, and a split is rare enough to pay for it.
     */
    private List<Restored> absorbedInto(String handle) {
        List<Restored> found = new ArrayList<>();
        String cursor = null;
        do {
            Map<String, Object> vars = new LinkedHashMap<>();
            if (cursor != null) {
                vars.put("cursor", cursor);
            }
            JsonNode page = require(gql.execute(ShopifyGraphQL.GROUPED_PRODUCTS, vars)).path("products");
            for (JsonNode n : page.path("nodes")) {
                if (!handle.equals(n.path("groupedInto").path("value").asText(null))) {
                    continue;
                }
                // The ids it had: the note, or — when a split stopped after giving them back — its own.
                Set<String> ids = new LinkedHashSet<>(parseIds(n.path("groupedIds").path("value").asText(null)));
                String psId = n.path("psId").path("value").asText(null);
                if (ids.isEmpty() && psId != null && !psId.isBlank()) {
                    ids.add(psId);
                }
                if (ids.isEmpty()) {
                    ids.addAll(parseIds(n.path("psIds").path("value").asText(null)));
                }
                if (!ids.isEmpty()) {
                    found.add(new Restored(n.path("id").asText(), n.path("handle").asText(),
                            n.path("status").asText(null), List.copyOf(ids)));
                }
            }
            JsonNode pageInfo = page.path("pageInfo");
            cursor = pageInfo.path("hasNextPage").asBoolean(false) ? pageInfo.path("endCursor").asText(null) : null;
        } while (cursor != null);
        return found;
    }

    /** A list metafield is a JSON array; Matrixify sheets wrote some as plain {@code A,B}. */
    private List<String> parseIds(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        if (raw.stripLeading().startsWith("[")) {
            try {
                for (JsonNode e : objectMapper.readTree(raw)) {
                    if (e.isTextual() && !e.asText().isBlank()) {
                        ids.add(e.asText().strip());
                    }
                }
                return ids;
            } catch (JsonProcessingException e) {
                log.warn("Unparseable id list {}: {}", raw, e.getMessage());
            }
        }
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                ids.add(part.strip());
            }
        }
        return ids;
    }

    private static List<ImportedProduct> holders(List<ImportedProduct> store, String id) {
        return store.stream().filter(p -> containsId(p.supplierIds(), id)).toList();
    }

    private static boolean containsId(List<String> ids, String id) {
        return ids.stream().anyMatch(other -> key(other).equals(key(id)));
    }

    private static String handles(List<ImportedProduct> products) {
        return products.stream().map(ImportedProduct::handle).collect(Collectors.joining(" and "));
    }

    // ---------------------------------------------------------------- writes

    private static void step(String handle, String what, Runnable write) {
        try {
            write.run();
        } catch (RuntimeException e) {
            throw new ShopifySyncException("Splitting " + handle + " stopped while " + what + ": "
                    + e.getMessage() + ". Whatever was written so far belongs to this same split, and "
                    + "applying it again finishes it.");
        }
    }

    private void deleteVariants(Plan plan) {
        JsonNode data = gql.execute(ShopifyGraphQL.VARIANTS_BULK_DELETE,
                Map.of("productId", plan.parent().gid(), "variantsIds", plan.removedVariantIds()));
        checkUserErrors(require(data).path("productVariantsBulkDelete"), "productVariantsBulkDelete");
    }

    /** The grouped product's remaining ids, the restored products' own, and the marks on all of them. */
    private List<Map<String, Object>> identityMetafields(Plan plan, boolean keepStoreImages) {
        List<Map<String, Object>> metafields = new ArrayList<>();
        String parent = plan.parent().gid();
        metafields.add(metafield(parent, ShopifyProductMapper.METAFIELD_NAMESPACE,
                ShopifyProductMapper.MF_PRODUCT_IDS, "list.single_line_text_field", json(plan.keptIds())));
        metafields.add(metafield(parent, ShopifyProductMapper.SYNC_NAMESPACE, MF_OWN_PARTS_ONLY, "boolean", "true"));
        for (Restored r : plan.restored()) {
            metafields.add(metafield(r.gid(), ShopifyProductMapper.METAFIELD_NAMESPACE,
                    ShopifyProductMapper.MF_PRODUCT_ID, "single_line_text_field", r.supplierIds().get(0)));
            metafields.add(metafield(r.gid(), ShopifyProductMapper.METAFIELD_NAMESPACE,
                    ShopifyProductMapper.MF_PRODUCT_IDS, "list.single_line_text_field", json(r.supplierIds())));
            metafields.add(metafield(r.gid(), ShopifyProductMapper.SYNC_NAMESPACE, MF_OWN_PARTS_ONLY,
                    "boolean", "true"));
            if (keepStoreImages) {
                metafields.add(metafield(r.gid(), ShopifyProductMapper.SYNC_NAMESPACE, MF_KEEP_STORE_IMAGES,
                        "boolean", "true"));
            }
        }
        return metafields;
    }

    private void setMetafields(List<Map<String, Object>> metafields) {
        for (List<Map<String, Object>> batch : batches(metafields)) {
            JsonNode data = gql.execute(ShopifyGraphQL.METAFIELDS_SET, Map.of("metafields", batch));
            checkUserErrors(require(data).path("metafieldsSet"), "metafieldsSet");
        }
    }

    private void setStatus(String gid, String status) {
        JsonNode data = gql.execute(ShopifyGraphQL.PRODUCT_STATUS, Map.of("id", gid, "status", status));
        checkUserErrors(require(data).path("productUpdate"), "productUpdate(status: " + status + ")");
    }

    private void deleteNotes(List<Restored> restored) {
        List<Map<String, Object>> identifiers = new ArrayList<>();
        for (Restored r : restored) {
            for (String key : List.of(ProductGroupService.MF_GROUPED_INTO, ProductGroupService.MF_GROUPED_IDS)) {
                identifiers.add(Map.of("ownerId", r.gid(), "namespace", ShopifyProductMapper.SYNC_NAMESPACE,
                        "key", key));
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
