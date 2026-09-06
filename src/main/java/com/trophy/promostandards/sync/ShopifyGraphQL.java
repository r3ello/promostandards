package com.trophy.promostandards.sync;

/**
 * Shopify Admin GraphQL operation strings used by the sync flow.
 *
 * <p>All operations here were validated against the Admin API {@code 2026-04} schema via the
 * {@code shopify-dev-mcp} {@code validate_graphql_codeblocks} tool. Note: {@code Query.product} takes
 * only {@code id} (no {@code handle} arg) — handle lookup goes through the {@code products(query:)}
 * connection ({@link #PRODUCT_BY_HANDLE}). Re-validate with that tool if you change a version.
 */
final class ShopifyGraphQL {

    private ShopifyGraphQL() {
    }

    /**
     * Look up an existing product (and its variants) by the deterministic handle. The Admin API's
     * {@code product} field only accepts {@code id}, so we search the products connection with a
     * {@code handle:} qualifier and exact-match the result.
     *
     * <p>Carries everything the write paths need about a product so they cost one lookup: variant
     * identity and options (variant creation), stock state (inventory), price and title, and the
     * per-variant {@code custom.promo_standard_id} that says which supplier id each variant is (the
     * quantity-discount write matches on it).
     */
    static final String PRODUCT_BY_HANDLE = """
            query ProductByHandle($query: String!, $locationId: ID!, $withLocation: Boolean!) {
              products(first: 1, query: $query) {
                nodes {
                  id
                  handle
                  title
                  media(first: 100) { nodes { id alt } }
                  legacySku: metafield(namespace: "migration", key: "legacy_sku") { value }
                  options { id name position optionValues { id name } }
                  variants(first: 100) {
                    nodes {
                      id
                      sku
                      title
                      price
                      selectedOptions { name value }
                      psId: metafield(namespace: "custom", key: "promo_standard_id") { value }
                      vendorSku: metafield(namespace: "trophy_sync", key: "vendor_sku") { value }
                      inventoryItem {
                        id
                        tracked
                        inventoryLevel(locationId: $locationId) @include(if: $withLocation) {
                          quantities(names: ["available"]) { name quantity }
                        }
                      }
                    }
                  }
                }
              }
            }
            """;

    /** Upsert a product with options, variants, media, and metafields in one synchronous call. */
    static final String PRODUCT_SET = """
            mutation ProductSet($input: ProductSetInput!, $synchronous: Boolean!) {
              productSet(input: $input, synchronous: $synchronous) {
                product {
                  id
                  handle
                  variants(first: 100) {
                    nodes { id sku inventoryItem { id } }
                  }
                }
                userErrors { field message }
              }
            }
            """;

    /**
     * Set available quantities at a location for a batch of inventory items.
     *
     * <p>Two API-2026-04 requirements are easy to miss and both reject the whole mutation: every
     * quantity must carry {@code changeFromQuantity} (the compare-and-set baseline that replaced the
     * old {@code ignoreCompareQuantity} input field), and the call must carry an idempotency key via
     * the {@code @idempotent} directive — which is also what makes the client's throttling retry
     * safe, since a retry resends the same key.
     */
    static final String INVENTORY_SET_QUANTITIES = """
            mutation InventorySet($input: InventorySetQuantitiesInput!, $idempotencyKey: String!) {
              inventorySetQuantities(input: $input) @idempotent(key: $idempotencyKey) {
                inventoryAdjustmentGroup { createdAt }
                userErrors { field message }
              }
            }
            """;

    /** Update variant prices in bulk for one product. */
    static final String VARIANTS_BULK_UPDATE = """
            mutation VariantsUpdate($productId: ID!, $variants: [ProductVariantsBulkInput!]!) {
              productVariantsBulkUpdate(productId: $productId, variants: $variants) {
                productVariants { id price }
                userErrors { field message }
              }
            }
            """;

    /** List the store's locations (to discover the inventory location GID for configuration). */
    static final String LOCATIONS = """
            query Locations {
              locations(first: 10) {
                nodes {
                  id
                  name
                  isActive
                  address { formatted }
                }
              }
            }
            """;

    /** List the store's product metafield definitions (the metafields other products already use). */
    static final String METAFIELD_DEFINITIONS = """
            query ProductMetafieldDefinitions($cursor: String) {
              metafieldDefinitions(ownerType: PRODUCT, first: 100, after: $cursor) {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id
                  name
                  namespace
                  key
                  description
                  type { name category }
                }
              }
            }
            """;

    /**
     * Sample a few products' value for one metafield, so the user can copy an example value when
     * picking metafields to import. Product search can't filter by metafield existence, so we sample
     * a page and keep the non-null values caller-side.
     */
    static final String METAFIELD_SAMPLES = """
            query MetafieldSamples($limit: Int!, $namespace: String!, $key: String!) {
              products(first: $limit) {
                nodes {
                  id
                  title
                  handle
                  metafield(namespace: $namespace, key: $key) { value type }
                }
              }
            }
            """;

    /**
     * Resolve a metaobject entry (e.g. the supplier's {@code promo_standard_supplier} entry) to its
     * GID, which is what a {@code metaobject_reference} metafield stores. Needs {@code read_metaobjects}.
     */
    static final String METAOBJECT_BY_HANDLE = """
            query MetaobjectByHandle($handle: MetaobjectHandleInput!) {
              metaobjectByHandle(handle: $handle) {
                id
                type
                handle
              }
            }
            """;

    /** Create a product metafield definition (idempotent: a TAKEN userError means it already exists). */
    static final String METAFIELD_DEFINITION_CREATE = """
            mutation MetafieldDefinitionCreate($definition: MetafieldDefinitionInput!) {
              metafieldDefinitionCreate(definition: $definition) {
                createdDefinition { id }
                userErrors { field message code }
              }
            }
            """;

    /** Find a Shopify order (and its open fulfillment orders) matching a supplier PO query. */
    /**
     * Fetch an order by GID — used once a PO has been matched, so later runs resolve it exactly
     * instead of re-running the {@code name:} search guess. Validated against 2026-04.
     */
    static final String ORDER_BY_ID = """
            query OrderById($id: ID!) {
              order(id: $id) {
                id
                name
                fulfillmentOrders(first: 20) {
                  nodes { id status }
                }
              }
            }
            """;

    static final String ORDER_BY_PO = """
            query OrderByPo($query: String!) {
              orders(first: 1, query: $query) {
                nodes {
                  id
                  name
                  fulfillmentOrders(first: 20) {
                    nodes { id status }
                  }
                }
              }
            }
            """;

    /** Create a fulfillment with carrier tracking against a set of fulfillment orders. */
    static final String FULFILLMENT_CREATE = """
            mutation FulfillmentCreate($fulfillment: FulfillmentInput!) {
              fulfillmentCreate(fulfillment: $fulfillment) {
                fulfillment { id status trackingInfo { number company url } }
                userErrors { field message }
              }
            }
            """;

    /** Write order-level metafields (used to stamp the latest supplier order status). */
    static final String METAFIELDS_SET = """
            mutation MetafieldsSet($metafields: [MetafieldsSetInput!]!) {
              metafieldsSet(metafields: $metafields) {
                metafields { id key }
                userErrors { field message }
              }
            }
            """;

    /**
     * Page through every PromoStandards-tagged product: those imported by this app AND those the
     * one-shot trophypartner migration created (which carry the same tag + metafield contract).
     * {@code ps_product_ids} is the full list of supplier ids a migrated product covers (canonical
     * included); {@code ps_source} says who created the product ({@code app} | {@code migration});
     * {@code discounts} is the published quantity-break ladder, so the catalog can show which
     * products already carry discounts without a second pass over the store. Its namespace and key
     * are variables because the consuming app names that metafield — see {@code discounts.*}.
     */
    static final String IMPORTED_PRODUCTS = """
            query ImportedProducts($cursor: String, $discountNamespace: String!, $discountKey: String!) {
              products(first: 100, after: $cursor, query: "tag:promostandards") {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id
                  handle
                  psId: metafield(namespace: "custom", key: "ps_product_id") { value }
                  psIds: metafield(namespace: "custom", key: "ps_product_ids") { value }
                  psSource: metafield(namespace: "custom", key: "ps_source") { value }
                  syncSource: metafield(namespace: "trophy_sync", key: "source") { value }
                  discounts: metafield(namespace: $discountNamespace, key: $discountKey) { value }
                }
              }
            }
            """;

    /**
     * Replace a product's images with the supplier's. Media is the one thing {@code productSet} would
     * be right for and cannot be used on: migrated products must never see it (it is declarative over
     * variants). {@code productUpdate} takes media without touching anything else.
     *
     * <p>The response returns the media it just created with their {@code alt} text, which is how a
     * variant is then matched to its own image — Shopify rewrites every URL on ingest, so the source
     * URL cannot be the join.
     */
    static final String PRODUCT_ADD_MEDIA = """
            mutation ProductAddMedia($id: ID!, $media: [CreateMediaInput!]) {
              productUpdate(product: {id: $id}, media: $media) {
                product { id media(first: 100) { nodes { id alt } } }
                userErrors { field message }
              }
            }
            """;

    /** Deletes files (product images included). The only way to drop media in 2026-04. */
    static final String FILE_DELETE = """
            mutation FileDelete($fileIds: [ID!]!) {
              fileDelete(fileIds: $fileIds) {
                deletedFileIds
                userErrors { field message }
              }
            }
            """;

    /**
     * A product's media with its ingestion status. Shopify downloads an image after the mutation
     * returns, and a variant cannot be pointed at one until it is {@code READY} — attaching earlier
     * is refused with "Non-ready media cannot be attached to variants".
     */
    static final String PRODUCT_MEDIA_STATUS = """
            query ProductMedia($id: ID!) {
              product(id: $id) {
                media(first: 100) { nodes { id alt status } }
              }
            }
            """;

    /** Points a variant at one of the product's images. */
    static final String VARIANT_APPEND_MEDIA = """
            mutation VariantAppendMedia($productId: ID!, $variantMedia: [ProductVariantAppendMediaInput!]!) {
              productVariantAppendMedia(productId: $productId, variantMedia: $variantMedia) {
                productVariants { id }
                userErrors { field message }
              }
            }
            """;

    /**
     * Remove product options. Used to undo this app's own doing: a product the supplier sells in one
     * variant was given Color/Size options with a single value each, which the storefront renders as
     * a selector with nothing to select. {@code DEFAULT} only deletes an option that has one value,
     * which is exactly the case here and refuses anything riskier.
     */
    static final String PRODUCT_OPTIONS_DELETE = """
            mutation ProductOptionsDelete($productId: ID!, $options: [ID!]!,
                                          $strategy: ProductOptionDeleteStrategy) {
              productOptionsDelete(productId: $productId, options: $options, strategy: $strategy) {
                deletedOptionsIds
                userErrors { field message code }
              }
            }
            """;

    /** Update a metafield definition (used to enable the uniqueValues capability on ps_product_id). */
    static final String METAFIELD_DEFINITION_UPDATE = """
            mutation MetafieldDefinitionUpdate($definition: MetafieldDefinitionUpdateInput!) {
              metafieldDefinitionUpdate(definition: $definition) {
                updatedDefinition { id }
                userErrors { field message code }
              }
            }
            """;

    /**
     * Rename a product option (and its values) in place. Used to turn a migrated product's default
     * {@code Title / Default Title} option into the {@code Color} option the supplier variants need,
     * without deleting the variant that carries the product's order history.
     * {@code LEAVE_AS_IS} keeps Shopify from creating or deleting variants behind our back.
     */
    static final String PRODUCT_OPTION_UPDATE = """
            mutation ProductOptionUpdate($productId: ID!, $option: OptionUpdateInput!,
                                         $optionValuesToUpdate: [OptionValueUpdateInput!],
                                         $variantStrategy: ProductOptionUpdateVariantStrategy) {
              productOptionUpdate(productId: $productId, option: $option,
                                  optionValuesToUpdate: $optionValuesToUpdate,
                                  variantStrategy: $variantStrategy) {
                product { id options { id name optionValues { id name } } }
                userErrors { field message code }
              }
            }
            """;

    /** Add an option (e.g. Size) to an existing product without touching its variants. */
    static final String PRODUCT_OPTIONS_CREATE = """
            mutation ProductOptionsCreate($productId: ID!, $options: [OptionCreateInput!]!,
                                          $variantStrategy: ProductOptionCreateVariantStrategy) {
              productOptionsCreate(productId: $productId, options: $options,
                                   variantStrategy: $variantStrategy) {
                product { id options { id name optionValues { id name } } }
                userErrors { field message code }
              }
            }
            """;

    /**
     * Add variants to an existing product. {@code PRESERVE_STANDALONE_VARIANT} keeps the product's
     * single existing variant — the migrated one we adopt — which the default strategy would delete.
     */
    static final String VARIANTS_BULK_CREATE = """
            mutation VariantsCreate($productId: ID!, $variants: [ProductVariantsBulkInput!]!,
                                    $strategy: ProductVariantsBulkCreateStrategy) {
              productVariantsBulkCreate(productId: $productId, variants: $variants, strategy: $strategy) {
                productVariants { id sku inventoryItem { id } }
                userErrors { field message }
              }
            }
            """;

    /**
     * Stock an inventory item at a location. A migrated variant is typically untracked and not
     * stocked anywhere, and {@code inventorySetQuantities} refuses an item the location does not
     * carry, so activation comes first. ({@code inventoryActivate} would need an idempotency key
     * as of API 2026-04; this mutation does not.)
     */
    static final String INVENTORY_ACTIVATE = """
            mutation InventoryActivate($inventoryItemId: ID!,
                                       $updates: [InventoryBulkToggleActivationInput!]!) {
              inventoryBulkToggleActivation(inventoryItemId: $inventoryItemId,
                                            inventoryItemUpdates: $updates) {
                inventoryItem { id }
                userErrors { field message }
              }
            }
            """;
}
