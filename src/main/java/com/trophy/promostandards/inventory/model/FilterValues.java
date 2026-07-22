package com.trophy.promostandards.inventory.model;

import java.util.List;

/**
 * Mirrors the PromoStandards 1.2.1 {@code GetFilterValuesReply}: the distinct values that may be
 * used to filter a {@code getInventoryLevels} call for a given product.
 *
 * @param productId  the supplier product id
 * @param colors     selectable colors ({@code FilterColorArray})
 * @param sizes      selectable sizes ({@code FilterSizeArray})
 * @param selections selectable generic selections ({@code FilterSelectionArray})
 */
public record FilterValues(String productId, List<String> colors, List<String> sizes, List<String> selections) {
}
