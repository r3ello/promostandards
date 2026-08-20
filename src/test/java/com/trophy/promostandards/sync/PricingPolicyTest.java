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

    // --- SUPPLIER_LIST: the supplier's own retail price wins ------------------------------------

    /**
     * Real PaceSetter figures for GM668A: net 88.20, and a published retail of 147.00 that matches
     * their public product page exactly. The invented 40% markup would have published 123.99 —
     * under the supplier's own suggested price.
     */
    @Test
    void publishesTheSupplierRetailPriceWhenThereIsOne() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, true));

        assertThat(policy.retailPrice(new BigDecimal("88.20"), new BigDecimal("147.00")))
                .isEqualByComparingTo("147.00");
    }

    /** A stated retail price is published as stated: rounding it would disagree with the supplier. */
    @Test
    void doesNotRoundTheSupplierRetailPrice() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, false));

        assertThat(policy.retailPrice(new BigDecimal("50.00"), new BigDecimal("123.00")))
                .isEqualByComparingTo("123.00");
    }

    /** No published retail (PaceSetter leaves most of the catalog without one) -> the markup. */
    @Test
    void fallsBackToTheMarkupWhenTheSupplierPublishesNoRetailPrice() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, false));

        assertThat(policy.retailPrice(new BigDecimal("9.50"), null)).isEqualByComparingTo("13.99");
    }

    /** MARKUP stays available for whoever wants to ignore the supplier's retail price. */
    @Test
    void markupStrategyIgnoresThePublishedRetailPrice() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false));

        assertThat(policy.retailPrice(new BigDecimal("88.20"), new BigDecimal("147.00")))
                .isEqualByComparingTo("123.48");
    }

    /** An unset strategy must not silently ignore the supplier's price. */
    @Test
    void defaultsToTheSupplierRetailPrice() {
        PricingPolicy policy = policy(new Pricing(null, new BigDecimal("40"), Rounding.NONE, false));

        assertThat(policy.retailPrice(new BigDecimal("88.20"), new BigDecimal("147.00")))
                .isEqualByComparingTo("147.00");
    }
}
