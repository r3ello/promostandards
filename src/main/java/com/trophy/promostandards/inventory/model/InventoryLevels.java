package com.trophy.promostandards.inventory.model;

import java.util.List;

/**
 * Mirrors the PromoStandards Inventory Service 1.2.1 {@code Reply}: a product plus its per-variation
 * inventory ({@code ProductVariationInventoryArray}).
 *
 * <p>Companion inventory ({@code ProductCompanionInventoryArray}) is not yet mapped.
 *
 * @param productId the supplier product id
 * @param parts     inventory for each product variation
 */
public record InventoryLevels(String productId, List<PartInventory> parts) {

	/**
	 * Mirrors {@code ProductVariationInventory}.
	 *
	 * @param partId            supplier part id ({@code partID})
	 * @param partDescription   free-text part description
	 * @param partBrand         part brand
	 * @param quantityAvailable quantity available (1.2.1 sends this as a string; null when absent or
	 *                          non-numeric)
	 * @param color             color attribute ({@code attributeColor})
	 * @param size              size attribute ({@code attributeSize})
	 * @param selection         generic selection attribute ({@code attributeSelection})
	 * @param entryType         record type (e.g. exact, alternate)
	 */
	public record PartInventory(String partId, String partDescription, String partBrand,
			Integer quantityAvailable, String color, String size, String selection, String entryType) {
	}
}
