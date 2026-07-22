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
     */
    static final String PRODUCT_BY_HANDLE = """
            query ProductByHandle($query: String!) {
              products(first: 1, query: $query) {
                nodes {
                  id
                  handle
                  variants(first: 100) {
                    nodes { id sku inventoryItem { id } }
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

    /** Set on-hand quantities at a location for a batch of inventory items. */
    static final String INVENTORY_SET_QUANTITIES = """
            mutation InventorySet($input: InventorySetQuantitiesInput!) {
              inventorySetQuantities(input: $input) {
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

    /** Page through products previously imported by this app (carry the ps_product_id metafield). */
    static final String IMPORTED_PRODUCTS = """
            query ImportedProducts($cursor: String) {
              products(first: 100, after: $cursor, query: "tag:promostandards") {
                pageInfo { hasNextPage endCursor }
                nodes {
                  id
                  handle
                  metafield(namespace: "custom", key: "ps_product_id") { value }
                }
              }
            }
            """;
}
