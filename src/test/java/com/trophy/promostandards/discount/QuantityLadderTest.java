package com.trophy.promostandards.discount;

import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.sync.PricingPolicy;
import com.trophy.promostandards.sync.SyncProperties;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The quantity ladder, on the real PaceSetter tables the client walked through.
 */
class QuantityLadderTest {

    private static final PricingPolicy POLICY = new PricingPolicy(new SyncProperties(
            "PaceSetter", "USD", "US", "en", SyncProperties.SkuStrategy.PART_SIZE,
            new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, false),
            new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null));

    /** {minQuantity, net, list} triples, the shape the Pricing service returns them in. */
    private static Configuration.PartPrice part(String partId, Object[]... breaks) {
        List<Configuration.PriceBreak> rows = java.util.Arrays.stream(breaks)
                .map(b -> new Configuration.PriceBreak((int) b[0], new BigDecimal(String.valueOf(b[1])),
                        new BigDecimal(String.valueOf(b[2])), "BX"))
                .toList();
        return new Configuration.PartPrice(partId, partId + " description", rows);
    }

    /** The client's own worked example: GI307, base 207.99, tiers 193.99 and 178.99. */
    @Test
    void buildsTheClientsWorkedExample() {
        Configuration.PartPrice gi307 = part("GI307",
                new Object[]{1, "124.80", "208.00"},
                new Object[]{3, "116.40", "194.00"},
                new Object[]{6, "107.40", "179.00"});

        QuantityLadder ladder = QuantityLadder.of(List.of(gi307), "GI307", POLICY);

        assertThat(ladder.basePrice()).isEqualByComparingTo("207.99");
        assertThat(ladder.minimumQuantity()).isEqualTo(1);
        assertThat(ladder.hasMinimumQuantity()).isFalse();
        assertThat(ladder.tiers()).hasSize(2);
        assertThat(ladder.tiers().get(0).quantity()).isEqualTo(3);
        assertThat(ladder.tiers().get(0).unitPrice()).isEqualByComparingTo("193.99");
        assertThat(ladder.tiers().get(0).amountOff()).isEqualByComparingTo("14.00");
        assertThat(ladder.tiers().get(1).quantity()).isEqualTo(6);
        assertThat(ladder.tiers().get(1).unitPrice()).isEqualByComparingTo("178.99");
        assertThat(ladder.tiers().get(1).amountOff()).isEqualByComparingTo("29.00");
    }

    /** A five-row table whose list prices are not whole numbers (CD1268). */
    @Test
    void chargesEveryTierThroughTheSameCharmPricing() {
        Configuration.PartPrice cd1268 = part("CD1268",
                new Object[]{1, "66.00", "110.00"},
                new Object[]{3, "51.06", "85.10"},
                new Object[]{6, "42.12", "70.20"},
                new Object[]{25, "37.38", "62.30"},
                new Object[]{50, "33.18", "55.30"});

        QuantityLadder ladder = QuantityLadder.of(List.of(cd1268), "CD1268", POLICY);

        assertThat(ladder.basePrice()).isEqualByComparingTo("109.99");
        assertThat(ladder.tiers()).extracting(t -> t.unitPrice().toPlainString())
                .containsExactly("84.99", "69.99", "61.99", "54.99");
        assertThat(ladder.tiers()).extracting(t -> t.amountOff().toPlainString())
                .containsExactly("25.00", "40.00", "48.00", "55.00");
    }

    /**
     * PaceSetter's CM297 family is priced from quantity 4 up: four is the minimum order, and the
     * ladder is measured from that first break, not from an imaginary single unit.
     */
    @Test
    void treatsAFirstBreakAboveOneAsAMinimumOrderQuantity() {
        Configuration.PartPrice cm297 = part("CM297BL",
                new Object[]{4, "66.00", "110.00"},
                new Object[]{12, "50.85", "84.75"},
                new Object[]{24, "46.89", "78.15"});

        QuantityLadder ladder = QuantityLadder.of(List.of(cm297), "CM297BL", POLICY);

        assertThat(ladder.minimumQuantity()).isEqualTo(4);
        assertThat(ladder.hasMinimumQuantity()).isTrue();
        assertThat(ladder.basePrice()).isEqualByComparingTo("109.99");
        assertThat(ladder.tiers()).extracting(QuantityLadder.Tier::quantity).containsExactly(12, 24);
        assertThat(ladder.tiers()).extracting(t -> t.amountOff().toPlainString())
                .containsExactly("26.00", "32.00");
    }

    /** One price break is a price, not a ladder — there is nothing to publish. */
    @Test
    void reportsNoDiscountsWhenNoBreakIsCheaper() {
        Configuration.PartPrice flat = part("GI586BL", new Object[]{1, "124.80", "208.00"});

        QuantityLadder ladder = QuantityLadder.of(List.of(flat), "GI586BL", POLICY);

        assertThat(ladder.hasDiscounts()).isFalse();
        assertThat(ladder.basePrice()).isEqualByComparingTo("207.99");
    }

    /** The id being synced picks the part, so a grouped product prices from its own row. */
    @Test
    void pricesFromTheRequestedPartNotTheFirstOne() {
        Configuration.PartPrice other = part("CM297BB", new Object[]{1, "10.00", "20.00"},
                new Object[]{10, "8.00", "16.00"});
        Configuration.PartPrice asked = part("CM297BL", new Object[]{1, "66.00", "110.00"},
                new Object[]{12, "50.85", "84.75"});

        QuantityLadder ladder = QuantityLadder.of(List.of(other, asked), "CM297BL", POLICY);

        assertThat(ladder.partId()).isEqualTo("CM297BL");
        assertThat(ladder.basePrice()).isEqualByComparingTo("109.99");
        // Parts on different ladders cannot both be expressed in one product-wide value.
        assertThat(ladder.uniformAcrossParts()).isFalse();
    }

    /** Parts that agree do not raise the flag — the common case for a PaceSetter family. */
    @Test
    void flagsNothingWhenEveryPartSharesTheLadder() {
        Object[][] rows = {new Object[]{1, "66.00", "110.00"}, new Object[]{12, "50.85", "84.75"}};
        QuantityLadder ladder = QuantityLadder.of(
                List.of(part("CM297BL", rows), part("CM297LB", rows)), "CM297BL", POLICY);

        assertThat(ladder.uniformAcrossParts()).isTrue();
    }
}
