package com.trophy.promostandards.sync.model;

/**
 * A product metafield definition already present in the store, surfaced to the UI so the user can
 * pick which metafields to attach when importing a product.
 *
 * @param namespace   metafield namespace (e.g. {@code custom})
 * @param key         metafield key
 * @param name        human-readable name shown in the Shopify admin
 * @param type        Shopify metafield type name (e.g. {@code single_line_text_field}, {@code json})
 * @param description optional definition description (may be {@code null})
 */
public record MetafieldDefinitionView(String namespace, String key, String name, String type,
                                      String description) {
}
