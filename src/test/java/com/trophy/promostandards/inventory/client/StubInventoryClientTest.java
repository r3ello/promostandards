package com.trophy.promostandards.inventory.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryRequests.Filter;
import com.trophy.promostandards.inventory.model.InventoryRequests.GetInventoryLevelsRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StubInventoryClientTest {

	private final StubInventoryClient client = new StubInventoryClient();

	@Test
	void returnsAllPartsWhenNoFilter() {
		InventoryLevels levels = client.getInventoryLevels(
				new GetInventoryLevelsRequest("1.2.1", "id", "pw", "ABC", null));

		assertThat(levels.productId()).isEqualTo("ABC");
		assertThat(levels.parts()).hasSize(3);
	}

	@Test
	void appliesSizeFilter() {
		InventoryLevels levels = client.getInventoryLevels(new GetInventoryLevelsRequest(
				"1.2.1", "id", "pw", "ABC", new Filter(null, List.of("M"), null)));

		assertThat(levels.parts()).singleElement()
				.satisfies(part -> assertThat(part.size()).isEqualTo("M"));
	}

	@Test
	void blankProductIdThrowsWithServiceMessage() {
		assertThatThrownBy(() -> client.getInventoryLevels(
				new GetInventoryLevelsRequest("1.2.1", "id", "pw", "  ", null)))
				.isInstanceOf(PromoStandardsClientException.class)
				.satisfies(ex -> {
					List<ServiceMessage> messages = ((PromoStandardsClientException) ex).getServiceMessages();
					assertThat(messages).singleElement()
							.satisfies(m -> assertThat(m.code()).isEqualTo(110));
				});
	}
}
