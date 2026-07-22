package com.trophy.promostandards.sync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test for the catalog read API against the default (stub) PromoStandards clients. Shopify
 * is unconfigured here, so {@code imported} must be null (not connected).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsProductIdsCheaply() throws Exception {
        // The list is intentionally cheap: ids only (no per-product aggregation). Shopify is
        // unconfigured, so imported is null.
        mockMvc.perform(get("/api/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId=='SAMPLE-001')]").exists())
                .andExpect(jsonPath("$[?(@.productId=='SAMPLE-001')].imported").value((Object) null));
    }

    @Test
    void returnsProductDetailWithInventoryAndPriceMatrix() throws Exception {
        mockMvc.perform(get("/api/catalog/products/SAMPLE-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value("SAMPLE-001"))
                .andExpect(jsonPath("$.title").value("Sample Polo Shirt"))
                // Raw inventory variation rows (3 from the stub), not collapsed.
                .andExpect(jsonPath("$.inventory.length()").value(3))
                .andExpect(jsonPath("$.inventory[?(@.partId=='SAMPLE-001-RED-S')].onHand").value(1200))
                // Full price-break matrix per part, plus computed retail (9.50 net * 1.40 -> 13.30 -> .99 = 13.99).
                .andExpect(jsonPath("$.pricing[?(@.partId=='SAMPLE-001-RED')].breaks.length()").value(3))
                .andExpect(jsonPath("$.pricing[?(@.partId=='SAMPLE-001-RED')].retail").value(13.99))
                // Charges from getAvailableCharges (stub returns SETUP-EMB, RUN-EMB).
                .andExpect(jsonPath("$.charges.length()").value(2));
    }
}
