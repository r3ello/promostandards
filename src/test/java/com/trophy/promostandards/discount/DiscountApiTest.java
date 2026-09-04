package com.trophy.promostandards.discount;

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
 * The discount web layer against the real context: that the {@code discounts.*} configuration binds,
 * and that a preview builds the metafield value from the stub supplier without a store, a token or
 * any write at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiscountApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DiscountProperties props;

    @Test
    void bindsTheConfiguration() {
        assertThat(props.isEnabled()).isTrue();
        assertThat(props.namespace()).isEqualTo("trophy_discount");
        assertThat(props.key()).isEqualTo("discount_tiers");
        assertThat(props.type()).isEqualTo("json");
        assertThat(props.qualifiedName()).isEqualTo("trophy_discount.discount_tiers");
    }

    /** The status endpoint says where discounts are written — the only thing there is to configure. */
    @Test
    void reportsWhereDiscountsArePublished() throws Exception {
        mockMvc.perform(get("/api/discounts/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.metafield").value("trophy_discount.discount_tiers"))
                .andExpect(jsonPath("$.type").value("json"));
    }

    /**
     * A preview needs neither the store nor a write: the stub supplier's table (12/48/144) becomes a
     * base tier at 12 and one tier per cheaper break, each carrying the unit price at that quantity.
     */
    @Test
    void previewsTheLadderOfAProductThatIsNotInTheStore() throws Exception {
        mockMvc.perform(get("/api/discounts/preview/SAMPLE-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("PREVIEWED"))
                .andExpect(jsonPath("$.metafield").value("trophy_discount.discount_tiers"))
                .andExpect(jsonPath("$.payload.currencyCode").value("USD"))
                .andExpect(jsonPath("$.payload.tiers[0].minQuantity").value(12))
                .andExpect(jsonPath("$.payload.tiers[0].discountAmount").value(0))
                .andExpect(jsonPath("$.payload.tiers[1].minQuantity").value(48))
                .andExpect(jsonPath("$.payload.tiers[2].minQuantity").value(144));
    }
}
