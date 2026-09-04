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
     * their public product page. The invented 40% markup would have published 123.99 — under the
     * supplier's own suggested price. The .99 ending is the store's, the 147 is the supplier's.
     */
    @Test
    void publishesTheSupplierRetailPriceWhenThereIsOne() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, true));

        assertThat(policy.retailPrice(new BigDecimal("88.20"), new BigDecimal("147.00")))
                .isEqualByComparingTo("146.99");
    }

    /**
     * Charm pricing a stated retail price goes DOWN, never up: 208.00 publishes as 207.99, so the
     * store stays at or below what the supplier asks. Real GI307 figures (net 124.80 / list 208.00),
     * the price the client's own worked example starts from.
     */
    @Test
    void charmPricesTheSupplierRetailPriceDownwards() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, false));

        assertThat(policy.retailPrice(new BigDecimal("124.80"), new BigDecimal("208.00")))
                .isEqualByComparingTo("207.99");
        // Not a whole number: 346.10 -> 345.99, not 346.99.
        assertThat(policy.retailPrice(new BigDecimal("207.66"), new BigDecimal("346.10")))
                .isEqualByComparingTo("345.99");
        // Already charm-priced: left exactly as it is.
        assertThat(policy.retailPrice(new BigDecimal("50.00"), new BigDecimal("84.99")))
                .isEqualByComparingTo("84.99");
        // No x.99 exists below a price under 1.00, so it is published untouched.
        assertThat(policy.retailPrice(new BigDecimal("0.30"), new BigDecimal("0.50")))
                .isEqualByComparingTo("0.50");
    }

    /** Rounding NONE still publishes a stated retail price exactly as stated. */
    @Test
    void doesNotRoundTheSupplierRetailPriceWhenRoundingIsOff() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NONE, false));

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

    /**
     * The quantity-break ladder published in the discount metafield is the difference between charm-priced
     * tiers, so the whole ladder has to come out of this one method. The client's worked example
     * (GI307): 207.99 base, tiers at 193.99 and 178.99 — i.e. 14.00 and 29.00 off.
     */
    @Test
    void chargesTheWholeQuantityLadderThroughTheSameRule() {
        PricingPolicy policy = policy(
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, false));

        BigDecimal base = policy.retailPrice(new BigDecimal("124.80"), new BigDecimal("208.00"));
        BigDecimal tier3 = policy.retailPrice(new BigDecimal("116.40"), new BigDecimal("194.00"));
        BigDecimal tier6 = policy.retailPrice(new BigDecimal("107.40"), new BigDecimal("179.00"));

        assertThat(base).isEqualByComparingTo("207.99");
        assertThat(tier3).isEqualByComparingTo("193.99");
        assertThat(tier6).isEqualByComparingTo("178.99");
        assertThat(base.subtract(tier3)).isEqualByComparingTo("14.00");
        assertThat(base.subtract(tier6)).isEqualByComparingTo("29.00");
    }

    /** An unset strategy must not silently ignore the supplier's price. */
    @Test
    void defaultsToTheSupplierRetailPrice() {
        PricingPolicy policy = policy(new Pricing(null, new BigDecimal("40"), Rounding.NONE, false));

        assertThat(policy.retailPrice(new BigDecimal("88.20"), new BigDecimal("147.00")))
                .isEqualByComparingTo("147.00");
    }
}
