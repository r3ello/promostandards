package com.trophy.promostandards.pricing.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.model.PricingRequests;
import com.trophy.promostandards.pricing.soap.GetFobPointsResponse;
import com.trophy.promostandards.pricing.soap.PricingAndConfigurationService;
import com.trophy.promostandards.pricing.soap.shared.ErrorMessage;
import org.junit.jupiter.api.Test;

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
