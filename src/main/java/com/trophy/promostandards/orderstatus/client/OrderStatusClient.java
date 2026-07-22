package com.trophy.promostandards.orderstatus.client;

import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests.GetOrderStatusDetailsRequest;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests.GetOrderStatusTypesRequest;
import com.trophy.promostandards.orderstatus.model.OrderStatusType;

import java.util.List;

/**
 * Client for the PromoStandards Order Status Service (1.0.0).
 *
 * <p>Mirrors the SOAP operations so {@link StubOrderStatusClient} can be swapped for the real
 * SOAP-backed {@link SoapOrderStatusClient} via {@code promostandards.order-status.mode=soap}.
 */
public interface OrderStatusClient {

	/** {@code getOrderStatusDetails} — status detail entries for an order or since a timestamp. */
	List<OrderStatus> getOrderStatusDetails(GetOrderStatusDetailsRequest request);

	/** {@code getOrderStatusTypes} — the supplier's supported order-status codes. */
	List<OrderStatusType> getOrderStatusTypes(GetOrderStatusTypesRequest request);
}
