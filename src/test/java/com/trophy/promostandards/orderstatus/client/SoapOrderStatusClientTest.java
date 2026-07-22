package com.trophy.promostandards.orderstatus.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests;
import com.trophy.promostandards.orderstatus.model.OrderStatusType;
import com.trophy.promostandards.orderstatus.soap.GetOrderStatusTypesResponse;
import com.trophy.promostandards.orderstatus.soap.OrderStatusService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SOAP→model mapping in {@link SoapOrderStatusClient}. The generated JAX-WS port
 * is mocked, so no network/endpoint is involved.
 */
class SoapOrderStatusClientTest {

	private final OrderStatusService port = mock(OrderStatusService.class);
	private final SoapOrderStatusClient client = new SoapOrderStatusClient(port);

	private static final OrderStatusRequests.GetOrderStatusTypesRequest TYPES_REQUEST =
			new OrderStatusRequests.GetOrderStatusTypesRequest("1.0.0", "id", "pw");

	@Test
	void mapsStatusTypes() {
		when(port.getOrderStatusTypes(any())).thenReturn(sampleResponse());

		List<OrderStatusType> types = client.getOrderStatusTypes(TYPES_REQUEST);

		assertThat(types).singleElement().satisfies(t -> {
			assertThat(t.id()).isEqualTo(60);
			assertThat(t.name()).isEqualTo("In Production");
		});
	}

	@Test
	void errorMessageBecomesException() {
		GetOrderStatusTypesResponse response = new GetOrderStatusTypesResponse();
		response.setErrorMessage("Authentication failed");
		when(port.getOrderStatusTypes(any())).thenReturn(response);

		assertThatThrownBy(() -> client.getOrderStatusTypes(TYPES_REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("Authentication failed");
	}

	@Test
	void transportFailureBecomesException() {
		when(port.getOrderStatusTypes(any())).thenThrow(new RuntimeException("connection refused"));

		assertThatThrownBy(() -> client.getOrderStatusTypes(TYPES_REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("getOrderStatusTypes call failed");
	}

	private static GetOrderStatusTypesResponse sampleResponse() {
		GetOrderStatusTypesResponse.StatusArray.Status status = new GetOrderStatusTypesResponse.StatusArray.Status();
		status.setId(60);
		status.setName("In Production");

		GetOrderStatusTypesResponse.StatusArray array = new GetOrderStatusTypesResponse.StatusArray();
		array.getStatus().add(status);

		GetOrderStatusTypesResponse response = new GetOrderStatusTypesResponse();
		response.setStatusArray(array);
		return response;
	}
}
