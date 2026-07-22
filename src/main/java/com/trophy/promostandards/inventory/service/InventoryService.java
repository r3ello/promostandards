package com.trophy.promostandards.inventory.service;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.inventory.client.InventoryClient;
import com.trophy.promostandards.inventory.model.FilterValues;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryRequests.Filter;
import com.trophy.promostandards.inventory.model.InventoryRequests.GetFilterValuesRequest;
import com.trophy.promostandards.inventory.model.InventoryRequests.GetInventoryLevelsRequest;
import org.springframework.stereotype.Service;

/**
 * Application service over the {@link InventoryClient}. Enriches caller input with the configured
 * credentials and {@code wsVersion} before delegating to the (stub or SOAP) client.
 */
@Service
public class InventoryService {

	private final InventoryClient client;
	private final PromoStandardsProperties properties;

	public InventoryService(InventoryClient client, PromoStandardsProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	public InventoryLevels getInventoryLevels(String productId, Filter filter) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getInventoryLevels(new GetInventoryLevelsRequest(
				properties.getInventory().getWsVersion(), creds.getId(), creds.getPassword(), productId, filter));
	}

	public FilterValues getFilterValues(String productId) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getFilterValues(new GetFilterValuesRequest(
				properties.getInventory().getWsVersion(), creds.getId(), creds.getPassword(), productId));
	}
}
