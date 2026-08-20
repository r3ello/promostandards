package com.trophy.promostandards.pricing.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Charge.ChargePrice;
import com.trophy.promostandards.pricing.model.Configuration;
import static com.trophy.promostandards.pricing.service.PricingService.PRICE_TYPE_LIST;
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

	/** One break at {@code net}, scaled by {@code factor} (1 for Net, 1/0.6 for List). */
	private static PriceBreak priced(int minQuantity, String net, BigDecimal factor) {
		BigDecimal price = new BigDecimal(net).multiply(factor)
				.setScale(2, java.math.RoundingMode.HALF_UP);
		// listPrice within a break stays null: PromoStandards carries the list price in its own
		// priceType=List response, not as a second column here (the WSDL has no such field).
		return new PriceBreak(minQuantity, price, null, "EA");
	}

	@Override
	public Configuration getConfigurationAndPricing(GetConfigurationAndPricingRequest request) {
		String productId = requireProductId(request.productId());
		String currency = request.currency() == null || request.currency().isBlank() ? "USD" : request.currency();
		String priceType = request.priceType() == null || request.priceType().isBlank() ? "Net" : request.priceType();
		// priceType is honoured, as the real service does: Net is what the distributor pays and List
		// the supplier's suggested retail. PaceSetter's discount is a flat 40% (net = list x 0.6), so
		// the stub mirrors that ratio. A stub that answered both with the same figures would hide the
		// worst failure this code can have — publishing the catalog at cost.
		BigDecimal factor = PRICE_TYPE_LIST.equalsIgnoreCase(priceType)
				? new BigDecimal("1.00").divide(new BigDecimal("0.60"), 4, java.math.RoundingMode.HALF_UP)
				: BigDecimal.ONE;
		List<PartPrice> partPrices = List.of(
				new PartPrice(productId + "-RED", "Red colorway", List.of(
						priced(12, "9.50", factor), priced(48, "8.25", factor), priced(144, "7.10", factor))),
				new PartPrice(productId + "-BLU", "Blue colorway", List.of(
						priced(12, "9.50", factor), priced(48, "8.25", factor))));
		// Two imprint locations, mirroring the shapes the real service sends: a rectangular area
		// (height x width) and a circular one (diameter), plus a stitch-count method.
		List<Configuration.Location> locations = List.of(
				new Configuration.Location(1, "Front Center", true, 1, 1, 2, List.of(
						new Configuration.Decoration(10, "Screen Print", "Rectangular",
								new BigDecimal("3.00"), new BigDecimal("4.50"), null, "Inches", true),
						new Configuration.Decoration(11, "Embroidery", "Rectangular",
								new BigDecimal("2.50"), new BigDecimal("3.50"), null, "Stitches", false))),
				new Configuration.Location(2, "Left Sleeve", false, 0, 1, 1, List.of(
						new Configuration.Decoration(12, "Laser Engrave", "Circle",
								null, null, new BigDecimal("2.00"), "Inches", true))));
		return new Configuration(productId, currency, priceType, partPrices, locations);
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
