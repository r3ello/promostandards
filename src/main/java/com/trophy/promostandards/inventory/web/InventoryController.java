package com.trophy.promostandards.inventory.web;

import com.trophy.promostandards.inventory.model.FilterValues;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryRequests.Filter;
import com.trophy.promostandards.inventory.service.InventoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST facade for the PromoStandards Inventory Service.
 */
@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

	private final InventoryService service;

	public InventoryController(InventoryService service) {
		this.service = service;
	}

	/** {@code getInventoryLevels} — optional {@code color}/{@code size}/{@code selection} filters. */
	@GetMapping("/{productId}/levels")
	public InventoryLevels getInventoryLevels(@PathVariable String productId,
			@RequestParam(required = false) List<String> color,
			@RequestParam(required = false) List<String> size,
			@RequestParam(required = false) List<String> selection) {
		Filter filter = (color == null && size == null && selection == null)
				? null
				: new Filter(color, size, selection);
		return service.getInventoryLevels(productId, filter);
	}

	/** {@code getFilterValues} — distinct filter values for the product. */
	@GetMapping("/{productId}/filter-values")
	public FilterValues getFilterValues(@PathVariable String productId) {
		return service.getFilterValues(productId);
	}
}
