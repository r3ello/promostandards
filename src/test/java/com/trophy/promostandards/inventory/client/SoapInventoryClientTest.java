package com.trophy.promostandards.inventory.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryRequests;
import com.trophy.promostandards.inventory.soap.InventoryService;
import com.trophy.promostandards.inventory.soap.Reply;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SOAP→model mapping in {@link SoapInventoryClient}. The generated JAX-WS port
 * is mocked, so no network/endpoint is involved.
 */
class SoapInventoryClientTest {

	private final InventoryService port = mock(InventoryService.class);
	private final SoapInventoryClient client = new SoapInventoryClient(port);

	private static final InventoryRequests.GetInventoryLevelsRequest REQUEST =
			new InventoryRequests.GetInventoryLevelsRequest("1.2.1", "id", "pw", "ABC", null);

	@Test
	void mapsInventoryLevels() {
		when(port.getInventoryLevels(any())).thenReturn(sampleReply());

		InventoryLevels levels = client.getInventoryLevels(REQUEST);

		// PaceSetter echoes the productID wrapped in literal quotes ("ABC"); it must be stripped.
		assertThat(levels.productId()).isEqualTo("ABC");
		assertThat(levels.parts()).singleElement().satisfies(part -> {
			assertThat(part.partId()).isEqualTo("ABC-RED-S");
			assertThat(part.color()).isEqualTo("Red");
			assertThat(part.size()).isEqualTo("S");
			assertThat(part.partBrand()).isEqualTo("Acme");
			assertThat(part.quantityAvailable()).isEqualTo(1200);
		});
	}

	@Test
	void errorMessageBecomesException() {
		Reply reply = new Reply();
		reply.setProductID("ABC");
		reply.setErrorMessage("Authentication failed");
		when(port.getInventoryLevels(any())).thenReturn(reply);

		assertThatThrownBy(() -> client.getInventoryLevels(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("Authentication failed");
	}

	@Test
	void transportFailureBecomesException() {
		when(port.getInventoryLevels(any())).thenThrow(new RuntimeException("connection refused"));

		assertThatThrownBy(() -> client.getInventoryLevels(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("getInventoryLevels call failed");
	}

	private static Reply sampleReply() {
		Reply.ProductVariationInventoryArray.ProductVariationInventory variation =
				new Reply.ProductVariationInventoryArray.ProductVariationInventory();
		variation.setPartID("ABC-RED-S");
		variation.setPartDescription("Red, Small");
		variation.setPartBrand("Acme");
		variation.setQuantityAvailable("1200");
		variation.setAttributeColor("Red");
		variation.setAttributeSize("S");
		variation.setEntryType("Exact");

		Reply.ProductVariationInventoryArray variationArray = new Reply.ProductVariationInventoryArray();
		variationArray.getProductVariationInventory().add(variation);

		Reply reply = new Reply();
		reply.setProductID("\"ABC\""); // supplier wraps the id in literal quotes
		reply.setProductVariationInventoryArray(variationArray);
		return reply;
	}
}
