package com.trophy.promostandards.ordershipment.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.model.OrderShipmentRequests;
import com.trophy.promostandards.ordershipment.soap.GetOrderShipmentNotificationResponse;
import com.trophy.promostandards.ordershipment.soap.OrderShipmentNotificationService;
import com.trophy.promostandards.ordershipment.soap.shared.ErrorMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SOAP→model mapping in {@link SoapOrderShipmentClient}. The generated JAX-WS
 * port is mocked, so no network/endpoint is involved.
 */
class SoapOrderShipmentClientTest {

	private final OrderShipmentNotificationService port = mock(OrderShipmentNotificationService.class);
	private final SoapOrderShipmentClient client = new SoapOrderShipmentClient(port);

	private static final OrderShipmentRequests.GetOrderShipmentNotificationRequest REQUEST =
			new OrderShipmentRequests.GetOrderShipmentNotificationRequest("1.0.0", "id", "pw", 1, "PO-1001", null);

	@Test
	void mapsOrderShipment() {
		when(port.getOrderShipmentNotification(any())).thenReturn(sampleResponse());

		List<OrderShipment> shipments = client.getOrderShipmentNotification(REQUEST);

		assertThat(shipments).singleElement().satisfies(s -> {
			assertThat(s.purchaseOrderNumber()).isEqualTo("PO-1001");
			assertThat(s.complete()).isTrue();
		});
	}

	@Test
	void errorMessageBecomesException() {
		GetOrderShipmentNotificationResponse response = new GetOrderShipmentNotificationResponse();
		ErrorMessage error = new ErrorMessage();
		error.setCode(150);
		error.setDescription("Authentication failed");
		response.setErrorMessage(error);
		when(port.getOrderShipmentNotification(any())).thenReturn(response);

		assertThatThrownBy(() -> client.getOrderShipmentNotification(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("Authentication failed")
				.satisfies(ex -> assertThat(((PromoStandardsClientException) ex).getServiceMessages())
						.singleElement()
						.satisfies(m -> assertThat(m.code()).isEqualTo(150)));
	}

	@Test
	void transportFailureBecomesException() {
		when(port.getOrderShipmentNotification(any())).thenThrow(new RuntimeException("connection refused"));

		assertThatThrownBy(() -> client.getOrderShipmentNotification(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("getOrderShipmentNotification call failed");
	}

	private static GetOrderShipmentNotificationResponse sampleResponse() {
		GetOrderShipmentNotificationResponse.OrderShipmentNotificationArray.OrderShipmentNotification notification =
				new GetOrderShipmentNotificationResponse.OrderShipmentNotificationArray.OrderShipmentNotification();
		notification.setPurchaseOrderNumber("PO-1001");
		notification.setComplete(true);

		GetOrderShipmentNotificationResponse.OrderShipmentNotificationArray array =
				new GetOrderShipmentNotificationResponse.OrderShipmentNotificationArray();
		array.getOrderShipmentNotification().add(notification);

		GetOrderShipmentNotificationResponse response = new GetOrderShipmentNotificationResponse();
		response.setOrderShipmentNotificationArray(array);
		return response;
	}
}
