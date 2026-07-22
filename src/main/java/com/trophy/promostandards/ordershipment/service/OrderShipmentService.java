package com.trophy.promostandards.ordershipment.service;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.ordershipment.client.OrderShipmentClient;
import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.model.OrderShipmentRequests.GetOrderShipmentNotificationRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Application service over the {@link OrderShipmentClient}. Applies configured credentials and
 * {@code wsVersion} and selects the appropriate {@code queryType} before delegating to the client.
 */
@Service
public class OrderShipmentService {

	private static final int QUERY_BY_PURCHASE_ORDER = 1;
	private static final int QUERY_BY_SHIP_DATE = 3;

	private final OrderShipmentClient client;
	private final PromoStandardsProperties properties;

	public OrderShipmentService(OrderShipmentClient client, PromoStandardsProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	/** Shipments for a specific distributor purchase order (queryType 1). */
	public List<OrderShipment> getByPurchaseOrder(String purchaseOrderNumber) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getOrderShipmentNotification(new GetOrderShipmentNotificationRequest(
				wsVersion(), creds.getId(), creds.getPassword(), QUERY_BY_PURCHASE_ORDER, purchaseOrderNumber, null));
	}

	/** Shipments shipped on or after the given timestamp (queryType 3). */
	public List<OrderShipment> getShippedSince(Instant shippedSince) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getOrderShipmentNotification(new GetOrderShipmentNotificationRequest(
				wsVersion(), creds.getId(), creds.getPassword(), QUERY_BY_SHIP_DATE, null, shippedSince));
	}

	private String wsVersion() {
		return properties.getOrderShipment().getWsVersion();
	}
}
