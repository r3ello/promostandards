package com.trophy.promostandards.productdata.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.productdata.model.ProductDataRequests;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.soap.GetProductSellableResponse;
import com.trophy.promostandards.productdata.soap.ProductDataService;
import com.trophy.promostandards.productdata.soap.shared.ErrorMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SOAP→model mapping in {@link SoapProductDataClient}. The generated JAX-WS port
 * is mocked, so no network/endpoint is involved.
 */
class SoapProductDataClientTest {

	private final ProductDataService port = mock(ProductDataService.class);
	private final SoapProductDataClient client = new SoapProductDataClient(port);

	private static final ProductDataRequests.GetProductSellableRequest REQUEST =
			new ProductDataRequests.GetProductSellableRequest("1.0.0", "id", "pw", null, true);

	@Test
	void mapsSellableProducts() {
		when(port.getProductSellable(any())).thenReturn(sampleResponse());

		List<ProductSellable> sellable = client.getProductSellable(REQUEST);

		assertThat(sellable).hasSize(2);
		assertThat(sellable).first().satisfies(item -> {
			assertThat(item.productId()).isEqualTo("ABC");
			assertThat(item.partId()).isEqualTo("ABC-RED");
			assertThat(item.sellable()).isTrue();
		});
	}

	@Test
	void errorMessageBecomesException() {
		GetProductSellableResponse response = new GetProductSellableResponse();
		ErrorMessage error = new ErrorMessage();
		error.setCode(150);
		error.setDescription("Authentication failed");
		response.setErrorMessage(error);
		when(port.getProductSellable(any())).thenReturn(response);

		assertThatThrownBy(() -> client.getProductSellable(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("Authentication failed")
				.satisfies(ex -> assertThat(((PromoStandardsClientException) ex).getServiceMessages())
						.singleElement()
						.satisfies(m -> assertThat(m.code()).isEqualTo(150)));
	}

	@Test
	void transportFailureBecomesException() {
		when(port.getProductSellable(any())).thenThrow(new RuntimeException("connection refused"));

		assertThatThrownBy(() -> client.getProductSellable(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("getProductSellable call failed");
	}

	private static GetProductSellableResponse sampleResponse() {
		GetProductSellableResponse.ProductSellableArray.ProductSellable red =
				new GetProductSellableResponse.ProductSellableArray.ProductSellable();
		red.setProductId("ABC");
		red.setPartId("ABC-RED");
		GetProductSellableResponse.ProductSellableArray.ProductSellable blue =
				new GetProductSellableResponse.ProductSellableArray.ProductSellable();
		blue.setProductId("ABC");
		blue.setPartId("ABC-BLU");

		GetProductSellableResponse.ProductSellableArray array = new GetProductSellableResponse.ProductSellableArray();
		array.getProductSellable().add(red);
		array.getProductSellable().add(blue);

		GetProductSellableResponse response = new GetProductSellableResponse();
		response.setProductSellableArray(array);
		return response;
	}
}
