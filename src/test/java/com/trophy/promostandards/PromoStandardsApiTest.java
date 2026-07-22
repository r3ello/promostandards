package com.trophy.promostandards;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end wiring test: exercises one primary endpoint per PromoStandards service against the
 * default (stub) clients, plus the validation error path.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromoStandardsApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void inventoryLevelsReturnsParts() throws Exception {
		mockMvc.perform(get("/api/inventory/SAMPLE-001/levels"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.productId").value("SAMPLE-001"))
				.andExpect(jsonPath("$.parts[0].quantityAvailable").value(1200));
	}

	@Test
	void inventoryLevelsFilterNarrowsResults() throws Exception {
		mockMvc.perform(get("/api/inventory/SAMPLE-001/levels").param("color", "Blue"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.parts.length()").value(1))
				.andExpect(jsonPath("$.parts[0].color").value("Blue"));
	}

	@Test
	void productReturnsDetail() throws Exception {
		mockMvc.perform(get("/api/products/SAMPLE-001"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.productId").value("SAMPLE-001"))
				.andExpect(jsonPath("$.parts.length()").value(2));
	}

	@Test
	void configurationAndPricingReturnsPriceBreaks() throws Exception {
		mockMvc.perform(get("/api/pricing/SAMPLE-001/configuration").param("currency", "USD"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.currency").value("USD"))
				.andExpect(jsonPath("$.partPrices[0].priceBreaks[0].minQuantity").value(12));
	}

	@Test
	void mediaContentFilterByType() throws Exception {
		mockMvc.perform(get("/api/media/SAMPLE-001").param("mediaType", "Document"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].mediaType").value("Document"));
	}
}
