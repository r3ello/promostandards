package com.trophy.promostandards.pricing.model;

/**
 * Request payloads for the Pricing and Configuration Service (1.0.0). Credentials and
 * {@code wsVersion} are supplied by the service layer from configuration.
 */
public final class PricingRequests {

	private PricingRequests() {
	}

	/**
	 * Mirrors {@code GetConfigurationAndPricingRequest}.
	 *
	 * @param priceType         "Net" or "List"
	 * @param configurationType "Blank" or "Decorated"
	 */
	public record GetConfigurationAndPricingRequest(String wsVersion, String id, String password, String productId,
			String currency, String fobId, String priceType, String localizationCountry, String localizationLanguage,
			String configurationType) {
	}

	/** Mirrors {@code GetAvailableChargesRequest}. */
	public record GetAvailableChargesRequest(String wsVersion, String id, String password, String productId,
			String localizationCountry, String localizationLanguage) {
	}

	/** Mirrors {@code GetFobPointsRequest}. */
	public record GetFobPointsRequest(String wsVersion, String id, String password, String productId,
			String localizationCountry, String localizationLanguage) {
	}
}
