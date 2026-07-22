package com.trophy.promostandards.orderstatus.service;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.orderstatus.client.OrderStatusClient;
import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests.GetOrderStatusDetailsRequest;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests.GetOrderStatusTypesRequest;
import com.trophy.promostandards.orderstatus.model.OrderStatusType;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Application service over the {@link OrderStatusClient}. Applies configured credentials and
 * {@code wsVersion} and selects the appropriate {@code queryType} before delegating to the client.
 */
@Service
public class OrderStatusService {

	private static final int QUERY_BY_PURCHASE_ORDER = 1;
	private static final int QUERY_BY_LAST_UPDATE = 3;

	private final OrderStatusClient client;
	private final PromoStandardsProperties properties;

	public OrderStatusService(OrderStatusClient client, PromoStandardsProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	/** Status details for a specific distributor purchase order (queryType 1). */
	public List<OrderStatus> getByPurchaseOrder(String purchaseOrderNumber) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getOrderStatusDetails(new GetOrderStatusDetailsRequest(
				wsVersion(), creds.getId(), creds.getPassword(), QUERY_BY_PURCHASE_ORDER, purchaseOrderNumber, null));
	}

	/** Status details changed on or after the given timestamp (queryType 3). */
	public List<OrderStatus> getUpdatedSince(Instant updatedSince) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getOrderStatusDetails(new GetOrderStatusDetailsRequest(
				wsVersion(), creds.getId(), creds.getPassword(), QUERY_BY_LAST_UPDATE, null, updatedSince));
	}

	/** The supplier's supported order-status codes. */
	public List<OrderStatusType> getStatusTypes() {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getOrderStatusTypes(
				new GetOrderStatusTypesRequest(wsVersion(), creds.getId(), creds.getPassword()));
	}

	private String wsVersion() {
		return properties.getOrderStatus().getWsVersion();
	}
}
