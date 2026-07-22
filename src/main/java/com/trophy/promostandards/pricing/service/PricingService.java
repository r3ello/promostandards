package com.trophy.promostandards.pricing.service;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.pricing.client.PricingClient;
import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.model.PricingRequests.GetAvailableChargesRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetConfigurationAndPricingRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetFobPointsRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Application service over the {@link PricingClient}. Applies configured credentials,
 * {@code wsVersion}, and localization defaults before delegating to the client.
 */
@Service
public class PricingService {

	private static final String DEFAULT_COUNTRY = "US";
	private static final String DEFAULT_LANGUAGE = "en";

	private final PricingClient client;
	private final PromoStandardsProperties properties;

	public PricingService(PricingClient client, PromoStandardsProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	public Configuration getConfigurationAndPricing(String productId, String currency, String fobId, String priceType,
			String configurationType, String country, String language) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getConfigurationAndPricing(new GetConfigurationAndPricingRequest(wsVersion(), creds.getId(),
				creds.getPassword(), productId, currency, fobId, priceType, country(country), language(language),
				configurationType));
	}

	public List<Charge> getAvailableCharges(String productId, String country, String language) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getAvailableCharges(new GetAvailableChargesRequest(wsVersion(), creds.getId(),
				creds.getPassword(), productId, country(country), language(language)));
	}

	public List<FobPoint> getFobPoints(String productId, String country, String language) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getFobPoints(new GetFobPointsRequest(wsVersion(), creds.getId(), creds.getPassword(),
				productId, country(country), language(language)));
	}

	private String wsVersion() {
		return properties.getPricing().getWsVersion();
	}

	private static String country(String country) {
		return country == null || country.isBlank() ? DEFAULT_COUNTRY : country;
	}

	private static String language(String language) {
		return language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
	}
}
