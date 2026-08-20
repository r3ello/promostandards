package com.trophy.promostandards.sync;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Autowired
    private CatalogGroupIndex groupIndex;

    @Test
    void listsProductIdsCheaply() throws Exception {
        // The list is intentionally cheap: ids only (no per-product aggregation). Shopify is
        // unconfigured, so imported is null.
        mockMvc.perform(get("/api/catalog/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.productId=='SAMPLE-001')]").exists())
                .andExpect(jsonPath("$[?(@.productId=='SAMPLE-001')].imported").value((Object) null))
                // The supplier's own close-out flag, crossed in from getProductCloseOut: the stub
                // discontinues TROPHY-SM (and LEGACY-009, which isn't sellable, so never appears).
                .andExpect(jsonPath("$[?(@.productId=='SAMPLE-001')].closeOut").value(false))
                .andExpect(jsonPath("$[?(@.productId=='TROPHY-SM')].closeOut").value(true))
                .andExpect(jsonPath("$[?(@.productId=='LEGACY-009')]").doesNotExist());
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
                // Full price-break matrix per part, plus the published retail. The supplier states one
                // (stub: net 9.50 with a 40% distributor discount -> list 15.83), so it is published as
                // stated rather than derived from the markup.
                .andExpect(jsonPath("$.pricing[?(@.partId=='SAMPLE-001-RED')].breaks.length()").value(3))
                .andExpect(jsonPath("$.pricing[?(@.partId=='SAMPLE-001-RED')].retail").value(15.83))
                // Charges from getAvailableCharges (stub returns SETUP-EMB, RUN-EMB).
                .andExpect(jsonPath("$.charges.length()").value(2))
                // Every service answered, so nothing is flagged as degraded.
                .andExpect(jsonPath("$.productDataMissing").value(false))
                .andExpect(jsonPath("$.warnings").isEmpty())
                // Imprint locations from the Pricing LocationArray, with their area dimensions.
                .andExpect(jsonPath("$.decorationLocations.length()").value(2))
                .andExpect(jsonPath("$.decorationLocations[?(@.name=='Front Center')].isDefault").value(true))
                .andExpect(jsonPath("$.decorationLocations[0].decorations[?(@.name=='Screen Print')].width").value(4.50))
                .andExpect(jsonPath("$.decorationLocations[1].decorations[?(@.name=='Laser Engrave')].diameter").value(2.00));
    }

    /**
     * Server-side search needs the mirror. Without a database the console filters the full list in
     * the browser, so the endpoint says it is unavailable (503) instead of returning an unfiltered
     * page that looks like a search result.
     */
    @Test
    void searchEndpointReportsItselfUnavailableWithoutTheDatabase() throws Exception {
        mockMvc.perform(get("/api/catalog/products/search").param("q", "polo"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("persistence")));
    }

    @Test
    void groupIndexUnionsTrophyFamilyFromCommonGrouping() {
        // Built synchronously against the real stub client: the three TROPHY-* ids the stub links via
        // Common Grouping collapse into one family; SAMPLE-001 stays standalone (not a family).
        assertThat(groupIndex.buildNow().groups())
                .anySatisfy(g -> assertThat(g.memberIds())
                        .containsExactlyInAnyOrder("TROPHY-SM", "TROPHY-MD", "TROPHY-LG"));
    }

    @Test
    void productGroupsEndpointReturnsView() throws Exception {
        mockMvc.perform(get("/api/catalog/product-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.groups").isArray());
    }
}
