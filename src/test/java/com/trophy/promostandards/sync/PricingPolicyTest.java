package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PricingPolicyTest {

    private static PricingPolicy policy(Pricing pricing) {
        return new PricingPolicy(new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE, pricing,
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null));
    }

    @Test
    void appliesMarkupAndNinetyNineRounding() {
        PricingPolicy policy = policy(new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NINETY_NINE, false));
        // 9.50 * 1.40 = 13.30 -> round up to x.99 -> 13.99
        assertThat(policy.retailPrice(new BigDecimal("9.50"), new BigDecimal("12.00")))
                .isEqualByComparingTo("13.99");
    }

    @Test
    void mapFloorPreventsPricingBelowListPrice() {
        PricingPolicy policy = policy(new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NINETY_NINE, true));
        // 8.25 * 1.40 = 11.55, below MAP 12.00 -> floored to 12.00 -> rounded to 12.99
        assertThat(policy.retailPrice(new BigDecimal("8.25"), new BigDecimal("12.00")))
                .isEqualByComparingTo("12.99");
    }

    @Test
    void markupOnlyWithoutRounding() {
        PricingPolicy policy = policy(new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false));
        assertThat(policy.retailPrice(new BigDecimal("10.00"), null)).isEqualByComparingTo("14.00");
    }

    @Test
    void nullSupplierPriceYieldsNull() {
        PricingPolicy policy = policy(new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false));
        assertThat(policy.retailPrice(null, new BigDecimal("12.00"))).isNull();
    }
}
