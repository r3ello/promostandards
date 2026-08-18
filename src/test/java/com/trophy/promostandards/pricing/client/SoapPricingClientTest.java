package com.trophy.promostandards.pricing.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.model.PricingRequests;
import com.trophy.promostandards.pricing.soap.Decoration;
import com.trophy.promostandards.pricing.soap.GetConfigurationAndPricingResponse;
import com.trophy.promostandards.pricing.soap.GetFobPointsResponse;
import com.trophy.promostandards.pricing.soap.Location;
import com.trophy.promostandards.pricing.soap.PricingAndConfigurationService;
import com.trophy.promostandards.pricing.soap.shared.DecorationUomType;
import com.trophy.promostandards.pricing.soap.shared.ErrorMessage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SOAP→model mapping in {@link SoapPricingClient}. The generated JAX-WS port is
 * mocked, so no network/endpoint is involved.
 */
class SoapPricingClientTest {

	private final PricingAndConfigurationService port = mock(PricingAndConfigurationService.class);
	private final SoapPricingClient client = new SoapPricingClient(port);

	private static final PricingRequests.GetFobPointsRequest FOB_REQUEST =
			new PricingRequests.GetFobPointsRequest("1.0.0", "id", "pw", "ABC", "US", "en");

	@Test
	void mapsFobPoints() {
		when(port.getFobPoints(any())).thenReturn(sampleResponse());

		List<FobPoint> points = client.getFobPoints(FOB_REQUEST);

		assertThat(points).singleElement().satisfies(p -> {
			assertThat(p.fobId()).isEqualTo("F1");
			assertThat(p.city()).isEqualTo("Newark");
			assertThat(p.state()).isEqualTo("NJ");
			assertThat(p.country()).isEqualTo("US");
			assertThat(p.postalCode()).isEqualTo("07097");
		});
	}

	/**
	 * The imprint areas: {@code LocationArray} → per-location {@code DecorationArray} with the area's
	 * geometry and dimensions. This is the structured form of the supplier's PDF spec sheets, and it
	 * used to be dropped on the floor — only partPrices were mapped.
	 */
	@Test
	void mapsDecorationLocationsWithImprintAreas() {
		GetConfigurationAndPricingResponse response = new GetConfigurationAndPricingResponse();
		com.trophy.promostandards.pricing.soap.Configuration config =
				new com.trophy.promostandards.pricing.soap.Configuration();
		config.setProductId("ABC");
		config.setPriceType("Net");

		Decoration screenPrint = new Decoration();
		screenPrint.setDecorationId(10);
		screenPrint.setDecorationName("Screen Print");
		screenPrint.setDecorationGeometry("Rectangular");
		screenPrint.setDecorationHeight(new BigDecimal("3.00"));
		screenPrint.setDecorationWidth(new BigDecimal("4.50"));
		screenPrint.setDecorationUom(DecorationUomType.INCHES);
		screenPrint.setDefaultDecoration(true);

		Decoration laser = new Decoration();
		laser.setDecorationId(12);
		laser.setDecorationName("Laser Engrave");
		laser.setDecorationGeometry("Circle");
		laser.setDecorationDiameter(new BigDecimal("2.00"));
		laser.setDecorationUom(DecorationUomType.INCHES);

		Location location = new Location();
		location.setLocationId(1);
		location.setLocationName("Front Center");
		location.setDefaultLocation(true);
		location.setDecorationsIncluded(1);
		location.setMinDecoration(1);
		location.setMaxDecoration(2);
		Location.DecorationArray decorations = new Location.DecorationArray();
		decorations.getDecoration().addAll(List.of(screenPrint, laser));
		location.setDecorationArray(decorations);

		com.trophy.promostandards.pricing.soap.Configuration.LocationArray locations =
				new com.trophy.promostandards.pricing.soap.Configuration.LocationArray();
		locations.getLocation().add(location);
		config.setLocationArray(locations);
		response.setConfiguration(config);
		when(port.getConfigurationAndPricing(any())).thenReturn(response);

		Configuration result = client.getConfigurationAndPricing(new PricingRequests
				.GetConfigurationAndPricingRequest("1.0.0", "id", "pw", "ABC", "USD", null, "Net", null, "US", "en"));

		assertThat(result.locations()).singleElement().satisfies(l -> {
			assertThat(l.locationName()).isEqualTo("Front Center");
			assertThat(l.defaultLocation()).isTrue();
			assertThat(l.maxDecoration()).isEqualTo(2);
			assertThat(l.decorations()).hasSize(2);
			// Rectangular area keeps height x width; circular keeps the diameter.
			assertThat(l.decorations().get(0)).satisfies(d -> {
				assertThat(d.decorationName()).isEqualTo("Screen Print");
				assertThat(d.geometry()).isEqualTo("Rectangular");
				assertThat(d.height()).isEqualByComparingTo("3.00");
				assertThat(d.width()).isEqualByComparingTo("4.50");
				assertThat(d.uom()).isEqualTo("Inches");
				assertThat(d.defaultDecoration()).isTrue();
			});
			assertThat(l.decorations().get(1)).satisfies(d -> {
				assertThat(d.diameter()).isEqualByComparingTo("2.00");
				assertThat(d.height()).isNull();
				assertThat(d.defaultDecoration()).isFalse();
			});
		});
	}

	/** A supplier that sends no LocationArray must map to an empty list, not a null or a failure. */
	@Test
	void toleratesAConfigurationWithNoLocations() {
		GetConfigurationAndPricingResponse response = new GetConfigurationAndPricingResponse();
		com.trophy.promostandards.pricing.soap.Configuration config =
				new com.trophy.promostandards.pricing.soap.Configuration();
		config.setProductId("ABC");
		response.setConfiguration(config);
		when(port.getConfigurationAndPricing(any())).thenReturn(response);

		Configuration result = client.getConfigurationAndPricing(new PricingRequests
				.GetConfigurationAndPricingRequest("1.0.0", "id", "pw", "ABC", "USD", null, "Net", null, "US", "en"));

		assertThat(result.locations()).isEmpty();
	}

	@Test
	void errorMessageBecomesException() {
		GetFobPointsResponse response = new GetFobPointsResponse();
		ErrorMessage error = new ErrorMessage();
		error.setCode(150);
		error.setDescription("Authentication failed");
		response.setErrorMessage(error);
		when(port.getFobPoints(any())).thenReturn(response);

		assertThatThrownBy(() -> client.getFobPoints(FOB_REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("Authentication failed")
				.satisfies(ex -> assertThat(((PromoStandardsClientException) ex).getServiceMessages())
						.singleElement()
						.satisfies(m -> assertThat(m.code()).isEqualTo(150)));
	}

	@Test
	void transportFailureBecomesException() {
		when(port.getFobPoints(any())).thenThrow(new RuntimeException("connection refused"));

		assertThatThrownBy(() -> client.getFobPoints(FOB_REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("getFobPoints call failed");
	}

	private static GetFobPointsResponse sampleResponse() {
		com.trophy.promostandards.pricing.soap.FobPoint fob = new com.trophy.promostandards.pricing.soap.FobPoint();
		fob.setFobId("F1");
		fob.setFobCity("Newark");
		fob.setFobState("NJ");
		fob.setFobCountry("US");
		fob.setFobPostalCode("07097");

		GetFobPointsResponse.FobPointArray array = new GetFobPointsResponse.FobPointArray();
		array.getFobPoint().add(fob);

		GetFobPointsResponse response = new GetFobPointsResponse();
		response.setFobPointArray(array);
		return response;
	}
}
