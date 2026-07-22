package com.trophy.promostandards.inventory.client;

import com.trophy.promostandards.inventory.model.FilterValues;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryRequests.GetFilterValuesRequest;
import com.trophy.promostandards.inventory.model.InventoryRequests.GetInventoryLevelsRequest;

/**
 * Client for the PromoStandards Inventory Service (1.2.1).
 *
 * <p>This interface intentionally mirrors the SOAP operations so the stub
 * ({@link StubInventoryClient}) can be replaced by a real SOAP-backed implementation without
 * touching the service or web layers. Activate a SOAP implementation by setting
 * {@code promostandards.inventory.mode=soap} (see README.md).
 */
public interface InventoryClient {

	/**
	 * {@code getInventoryLevels} — current inventory for a product, optionally filtered.
	 *
	 * @throws com.trophy.promostandards.common.PromoStandardsClientException if the supplier
	 *         returns an error or the call fails
	 */
	InventoryLevels getInventoryLevels(GetInventoryLevelsRequest request);

	/**
	 * {@code getFilterValues} — the distinct part/size/color values usable as inventory filters.
	 *
	 * @throws com.trophy.promostandards.common.PromoStandardsClientException if the supplier
	 *         returns an error or the call fails
	 */
	FilterValues getFilterValues(GetFilterValuesRequest request);
}
