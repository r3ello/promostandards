package com.trophy.promostandards.sync.model;

/**
 * An example value for one metafield, taken from a product that already has it, so the user can copy
 * an existing value when picking metafields to import.
 *
 * @param productTitle the title of the product the value came from
 * @param value        the metafield value on that product
 */
public record MetafieldSample(String productTitle, String value) {
}
