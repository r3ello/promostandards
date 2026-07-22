package com.trophy.promostandards.inventory.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.inventory.model.FilterValues;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryLevels.PartInventory;
import com.trophy.promostandards.inventory.model.InventoryRequests.GetFilterValuesRequest;
import com.trophy.promostandards.inventory.model.InventoryRequests.GetInventoryLevelsRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-memory stub for {@link InventoryClient}. Returns deterministic sample data so the REST API
 * is fully exercisable before a real SOAP client exists.
 *
 * <p>Active by default; deactivates when {@code promostandards.inventory.mode=soap}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.inventory", name = "mode", havingValue = "stub",
		matchIfMissing = true)
public class StubInventoryClient implements InventoryClient {

	@Override
	public InventoryLevels getInventoryLevels(GetInventoryLevelsRequest request) {
		String productId = requireProductId(request.productId());
		List<PartInventory> parts = List.of(
				new PartInventory(productId + "-RED-S", "Red, Small", "Acme", 1200, "Red", "S", null, "Exact"),
				new PartInventory(productId + "-RED-M", "Red, Medium", "Acme", 350, "Red", "M", null, "Exact"),
				new PartInventory(productId + "-BLU-S", "Blue, Small", "Acme", 0, "Blue", "S", null, "Exact"));
		return new InventoryLevels(productId, applyFilter(parts, request));
	}

	@Override
	public FilterValues getFilterValues(GetFilterValuesRequest request) {
		String productId = requireProductId(request.productId());
		return new FilterValues(productId,
				List.of("Red", "Blue"),
				List.of("S", "M"),
				List.of());
	}

	private static List<PartInventory> applyFilter(List<PartInventory> parts, GetInventoryLevelsRequest request) {
		if (request.filter() == null) {
			return parts;
		}
		List<String> colors = request.filter().colors();
		List<String> sizes = request.filter().sizes();
		List<String> selections = request.filter().selections();
		return parts.stream()
				.filter(p -> colors == null || colors.isEmpty() || colors.contains(p.color()))
				.filter(p -> sizes == null || sizes.isEmpty() || sizes.contains(p.size()))
				.filter(p -> selections == null || selections.isEmpty() || selections.contains(p.selection()))
				.toList();
	}

	private static String requireProductId(String productId) {
		if (productId == null || productId.isBlank()) {
			throw new PromoStandardsClientException("productId is required",
					List.of(ServiceMessage.error(110, "Required field productId is missing")));
		}
		return productId;
	}
}
