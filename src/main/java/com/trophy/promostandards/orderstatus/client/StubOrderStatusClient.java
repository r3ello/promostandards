package com.trophy.promostandards.orderstatus.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.model.OrderStatus.OrderStatusDetail;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests.GetOrderStatusDetailsRequest;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests.GetOrderStatusTypesRequest;
import com.trophy.promostandards.orderstatus.model.OrderStatusType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * In-memory stub for {@link OrderStatusClient}. Active by default; deactivates when
 * {@code promostandards.order-status.mode=soap}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.order-status", name = "mode", havingValue = "stub",
		matchIfMissing = true)
public class StubOrderStatusClient implements OrderStatusClient {

	@Override
	public List<OrderStatus> getOrderStatusDetails(GetOrderStatusDetailsRequest request) {
		String poNumber = resolvePoNumber(request);
		return List.of(new OrderStatus(poNumber, List.of(
				new OrderStatusDetail("FO-7788", 60, "In Production",
						Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-25T00:00:00Z"),
						"On schedule", false, Instant.parse("2026-06-19T12:00:00Z")))));
	}

	@Override
	public List<OrderStatusType> getOrderStatusTypes(GetOrderStatusTypesRequest request) {
		return List.of(
				new OrderStatusType(10, "Order Received"),
				new OrderStatusType(60, "In Production"),
				new OrderStatusType(75, "Partial Shipment"),
				new OrderStatusType(80, "Complete"),
				new OrderStatusType(99, "Canceled"));
	}

	private static String resolvePoNumber(GetOrderStatusDetailsRequest request) {
		// queryType 1/2 query by PO/SO number, which is required for those query types.
		if (request.queryType() == 1 || request.queryType() == 2) {
			if (request.referenceNumber() == null || request.referenceNumber().isBlank()) {
				throw new PromoStandardsClientException("referenceNumber is required for queryType 1 or 2",
						List.of(ServiceMessage.error(110, "Required field referenceNumber is missing")));
			}
			return request.referenceNumber();
		}
		return "PO-1001";
	}
}
