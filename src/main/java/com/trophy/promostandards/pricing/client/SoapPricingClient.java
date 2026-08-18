package com.trophy.promostandards.pricing.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.model.PricingRequests;
import com.trophy.promostandards.pricing.soap.GetAvailableChargesRequest;
import com.trophy.promostandards.pricing.soap.GetAvailableChargesResponse;
import com.trophy.promostandards.pricing.soap.GetConfigurationAndPricingRequest;
import com.trophy.promostandards.pricing.soap.GetConfigurationAndPricingResponse;
import com.trophy.promostandards.pricing.soap.GetFobPointsRequest;
import com.trophy.promostandards.pricing.soap.GetFobPointsResponse;
import com.trophy.promostandards.pricing.soap.PricingAndConfigurationService;
import com.trophy.promostandards.pricing.soap.iso4217.CurrencyCodeType;
import com.trophy.promostandards.pricing.soap.shared.ErrorMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * SOAP-backed {@link PricingClient} for the Pricing and Configuration Service (1.0.0), implemented
 * over the CXF-generated JAX-WS port. Active when {@code promostandards.pricing.mode=soap}; the stub
 * ({@link StubPricingClient}) is active otherwise.
 *
 * <p>Maps the application's model records to/from the generated SOAP types. A 1.0.0 response carries
 * an {@code ErrorMessage} (code + description) on failure; its presence — or any transport/SOAP
 * fault — is surfaced as a {@link PromoStandardsClientException}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.pricing", name = "mode", havingValue = "soap")
public class SoapPricingClient implements PricingClient {

	private static final String DEFAULT_CURRENCY = "USD";

	private final PricingAndConfigurationService port;

	public SoapPricingClient(PricingAndConfigurationService port) {
		this.port = port;
	}

	@Override
	public Configuration getConfigurationAndPricing(PricingRequests.GetConfigurationAndPricingRequest request) {
		GetConfigurationAndPricingRequest soapRequest = new GetConfigurationAndPricingRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setProductId(request.productId());
		soapRequest.setCurrency(toCurrency(request.currency()));
		soapRequest.setFobId(request.fobId());
		soapRequest.setPriceType(request.priceType());
		soapRequest.setLocalizationCountry(request.localizationCountry());
		soapRequest.setLocalizationLanguage(request.localizationLanguage());
		soapRequest.setConfigurationType(request.configurationType());

		GetConfigurationAndPricingResponse response =
				call(() -> port.getConfigurationAndPricing(soapRequest), "getConfigurationAndPricing");
		throwIfError(response.getErrorMessage());
		var config = response.getConfiguration();
		if (config == null) {
			throw new PromoStandardsClientException(
					"getConfigurationAndPricing returned no configuration for productId=" + request.productId());
		}

		List<Configuration.PartPrice> partPrices = new ArrayList<>();
		if (config.getPartArray() != null) {
			for (var part : config.getPartArray().getPart()) {
				List<Configuration.PriceBreak> breaks = new ArrayList<>();
				if (part.getPartPriceArray() != null) {
					for (var pp : part.getPartPriceArray().getPartPrice()) {
						breaks.add(new Configuration.PriceBreak(pp.getMinQuantity(), pp.getPrice(), null,
								pp.getPriceUom() != null ? pp.getPriceUom().value() : null));
					}
				}
				partPrices.add(new Configuration.PartPrice(part.getPartId(), part.getPartDescription(), breaks));
			}
		}
		String currency = config.getCurrency() != null ? config.getCurrency().value() : request.currency();
		return new Configuration(config.getProductId(), currency, config.getPriceType(), partPrices,
				toLocations(config.getLocationArray()));
	}

	/**
	 * Maps the {@code LocationArray}: the imprint locations and, per location, the decoration methods
	 * valid there with their imprint-area geometry and dimensions. This is the structured form of
	 * what suppliers otherwise only publish as a PDF spec sheet.
	 */
	private static List<Configuration.Location> toLocations(
			com.trophy.promostandards.pricing.soap.Configuration.LocationArray locationArray) {
		List<Configuration.Location> locations = new ArrayList<>();
		if (locationArray == null) {
			return locations;
		}
		for (var location : locationArray.getLocation()) {
			List<Configuration.Decoration> decorations = new ArrayList<>();
			if (location.getDecorationArray() != null) {
				for (var decoration : location.getDecorationArray().getDecoration()) {
					decorations.add(new Configuration.Decoration(
							decoration.getDecorationId(),
							decoration.getDecorationName(),
							decoration.getDecorationGeometry(),
							decoration.getDecorationHeight(),
							decoration.getDecorationWidth(),
							decoration.getDecorationDiameter(),
							decoration.getDecorationUom() != null ? decoration.getDecorationUom().value() : null,
							Boolean.TRUE.equals(decoration.isDefaultDecoration())));
				}
			}
			locations.add(new Configuration.Location(location.getLocationId(), location.getLocationName(),
					location.isDefaultLocation(), location.getDecorationsIncluded(),
					location.getMinDecoration(), location.getMaxDecoration(), decorations));
		}
		return locations;
	}

	@Override
	public List<Charge> getAvailableCharges(PricingRequests.GetAvailableChargesRequest request) {
		GetAvailableChargesRequest soapRequest = new GetAvailableChargesRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setProductId(request.productId());
		soapRequest.setLocalizationCountry(request.localizationCountry());
		soapRequest.setLocalizationLanguage(request.localizationLanguage());

		GetAvailableChargesResponse response = call(() -> port.getAvailableCharges(soapRequest), "getAvailableCharges");
		throwIfError(response.getErrorMessage());
		List<Charge> result = new ArrayList<>();
		if (response.getAvailableChargeArray() != null) {
			for (var charge : response.getAvailableChargeArray().getAvailableCharge()) {
				result.add(new Charge(String.valueOf(charge.getChargeId()), charge.getChargeName(),
						charge.getChargeType() != null ? charge.getChargeType().value() : null, List.of()));
			}
		}
		return result;
	}

	@Override
	public List<FobPoint> getFobPoints(PricingRequests.GetFobPointsRequest request) {
		GetFobPointsRequest soapRequest = new GetFobPointsRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setProductId(request.productId());
		soapRequest.setLocalizationCountry(request.localizationCountry());
		soapRequest.setLocalizationLanguage(request.localizationLanguage());

		GetFobPointsResponse response = call(() -> port.getFobPoints(soapRequest), "getFobPoints");
		throwIfError(response.getErrorMessage());
		List<FobPoint> result = new ArrayList<>();
		if (response.getFobPointArray() != null) {
			for (var fob : response.getFobPointArray().getFobPoint()) {
				result.add(new FobPoint(fob.getFobId(), null, fob.getFobCity(), fob.getFobState(),
						fob.getFobCountry(), fob.getFobPostalCode()));
			}
		}
		return result;
	}

	// --- helpers ---------------------------------------------------------------------------

	private static CurrencyCodeType toCurrency(String currency) {
		String code = (currency == null || currency.isBlank()) ? DEFAULT_CURRENCY : currency.trim();
		return CurrencyCodeType.fromValue(code);
	}

	private static <T> T call(Supplier<T> soapCall, String operation) {
		try {
			return soapCall.get();
		}
		catch (RuntimeException ex) {
			throw new PromoStandardsClientException(operation + " call failed: " + ex.getMessage(), ex);
		}
	}

	private static void throwIfError(ErrorMessage errorMessage) {
		if (errorMessage == null) {
			return;
		}
		ServiceMessage message = ServiceMessage.error(errorMessage.getCode(), errorMessage.getDescription());
		throw new PromoStandardsClientException(
				"PromoStandards pricing service returned an error: " + errorMessage.getDescription(), List.of(message));
	}
}
