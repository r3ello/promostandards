package com.trophy.promostandards.inventory.model;

import java.util.List;

/**
 * Request payloads for the Inventory Service (1.2.1). Credentials ({@code id}/{@code password})
 * and {@code wsVersion} are populated by the service layer from configuration so REST callers
 * never handle them.
 */
public final class InventoryRequests {

	private InventoryRequests() {
	}

	/**
	 * Mirrors the 1.2.1 {@code getInventoryLevels} {@code Request}.
	 *
	 * @param wsVersion PromoStandards WSDL version
	 * @param id        account id
	 * @param password  account password
	 * @param productId supplier product id
	 * @param filter    optional filter restricting the colors/sizes/selections returned (may be null)
	 */
	public record GetInventoryLevelsRequest(String wsVersion, String id, String password, String productId,
			Filter filter) {
	}

	/** Mirrors the 1.2.1 {@code GetFilterValuesRequest}. */
	public record GetFilterValuesRequest(String wsVersion, String id, String password, String productId) {
	}

	/**
	 * Optional {@code Filter} narrowing an inventory query, matching the 1.2.1 request's
	 * {@code FilterColorArray}/{@code FilterSizeArray}/{@code FilterSelectionArray}. Null/empty lists
	 * mean "no restriction".
	 */
	public record Filter(List<String> colors, List<String> sizes, List<String> selections) {
	}
}
