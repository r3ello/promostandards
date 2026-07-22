package com.trophy.promostandards.pricing.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Charge.ChargePrice;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.model.Configuration.PartPrice;
import com.trophy.promostandards.pricing.model.Configuration.PriceBreak;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.model.PricingRequests.GetAvailableChargesRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetConfigurationAndPricingRequest;
import com.trophy.promostandards.pricing.model.PricingRequests.GetFobPointsRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * In-memory stub for {@link PricingClient}. Active by default; deactivates when
 * {@code promostandards.pricing.mode=soap}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.pricing", name = "mode", havingValue = "stub",
		matchIfMissing = true)
public class StubPricingClient implements PricingClient {

	@Override
	public Configuration getConfigurationAndPricing(GetConfigurationAndPricingRequest request) {
		String productId = requireProductId(request.productId());
		String currency = request.currency() == null || request.currency().isBlank() ? "USD" : request.currency();
		String priceType = request.priceType() == null || request.priceType().isBlank() ? "Net" : request.priceType();
		List<PartPrice> partPrices = List.of(
				new PartPrice(productId + "-RED", "Red colorway", List.of(
						new PriceBreak(12, new BigDecimal("9.50"), new BigDecimal("12.00"), "EA"),
						new PriceBreak(48, new BigDecimal("8.25"), new BigDecimal("12.00"), "EA"),
						new PriceBreak(144, new BigDecimal("7.10"), new BigDecimal("12.00"), "EA"))),
				new PartPrice(productId + "-BLU", "Blue colorway", List.of(
						new PriceBreak(12, new BigDecimal("9.50"), new BigDecimal("12.00"), "EA"),
						new PriceBreak(48, new BigDecimal("8.25"), new BigDecimal("12.00"), "EA"))));
		return new Configuration(productId, currency, priceType, partPrices);
	}

	@Override
	public List<Charge> getAvailableCharges(GetAvailableChargesRequest request) {
		requireProductId(request.productId());
		return List.of(
				new Charge("SETUP-EMB", "Embroidery setup", "Setup", List.of(
						new ChargePrice(1, new BigDecimal("55.00"), new BigDecimal("55.00"), "EA"))),
				new Charge("RUN-EMB", "Embroidery run charge", "Run", List.of(
						new ChargePrice(12, new BigDecimal("4.00"), new BigDecimal("5.00"), "EA"),
						new ChargePrice(144, new BigDecimal("3.00"), new BigDecimal("5.00"), "EA"))));
	}

	@Override
	public List<FobPoint> getFobPoints(GetFobPointsRequest request) {
		return List.of(
				new FobPoint("FOB-NJ", "Jersey City DC", "Jersey City", "NJ", "US", "07097"),
				new FobPoint("FOB-CA", "Los Angeles DC", "Los Angeles", "CA", "US", "90001"));
	}

	private static String requireProductId(String productId) {
		if (productId == null || productId.isBlank()) {
			throw new PromoStandardsClientException("productId is required",
					List.of(ServiceMessage.error(110, "Required field productId is missing")));
		}
		return productId;
	}
}
