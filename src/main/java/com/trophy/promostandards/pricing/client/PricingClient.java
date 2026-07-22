package com.trophy.promostandards.pricing.client;

import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.model.PricingRequests.GetAvailableChargesRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetConfigurationAndPricingRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetFobPointsRequest;

import java.util.List;

/**
 * Client for the PromoStandards Pricing and Configuration Service (1.0.0).
 *
 * <p>Mirrors the SOAP operations so {@link StubPricingClient} can be swapped for a real
 * SOAP-backed implementation via {@code promostandards.pricing.mode=soap}.
 */
public interface PricingClient {

	/** {@code getConfigurationAndPricing} — price breaks for a product's parts. */
	Configuration getConfigurationAndPricing(GetConfigurationAndPricingRequest request);

	/** {@code getAvailableCharges} — charges (setup, run, freight, …) for a product. */
	List<Charge> getAvailableCharges(GetAvailableChargesRequest request);

	/** {@code getFobPoints} — FOB shipping origins for a product. */
	List<FobPoint> getFobPoints(GetFobPointsRequest request);
}
