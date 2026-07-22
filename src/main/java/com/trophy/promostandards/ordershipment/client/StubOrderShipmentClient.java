package com.trophy.promostandards.ordershipment.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.model.OrderShipment.ShipmentItem;
import com.trophy.promostandards.ordershipment.model.OrderShipment.ShipmentPackage;
import com.trophy.promostandards.ordershipment.model.OrderShipmentRequests.GetOrderShipmentNotificationRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * In-memory stub for {@link OrderShipmentClient}. Active by default; deactivates when
 * {@code promostandards.order-shipment.mode=soap}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.order-shipment", name = "mode", havingValue = "stub",
		matchIfMissing = true)
public class StubOrderShipmentClient implements OrderShipmentClient {

	@Override
	public List<OrderShipment> getOrderShipmentNotification(GetOrderShipmentNotificationRequest request) {
		String poNumber = resolvePoNumber(request);
		return List.of(new OrderShipment(poNumber, true, List.of(
				new ShipmentPackage("SO-5001", "1Z999AA10123456784", "UPS", "Ground",
						Instant.parse("2026-06-18T15:00:00Z"), "Newark", "NJ", "07097", "US",
						List.of(new ShipmentItem("SAMPLE-001", "SAMPLE-001-RED", new BigDecimal("100")))))));
	}

	private static String resolvePoNumber(GetOrderShipmentNotificationRequest request) {
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
