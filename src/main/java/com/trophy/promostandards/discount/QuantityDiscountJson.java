package com.trophy.promostandards.discount;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The value written into the quantity-discount metafield: the supplier's price table as the
 * storefront needs to read it. The real CM373BS entry, for the table
 * {@code 25 -> $19.99, 75 -> $18.99, 150 -> $15.99}:
 *
 * <pre>
 * {"currencyCode":"USD","tiers":[{"minQuantity":25,"discountAmount":0},
 *                                {"minQuantity":75,"discountAmount":1},
 *                                {"minQuantity":150,"discountAmount":4}]}
 * </pre>
 *
 * <p><b>{@code discountAmount} is how much comes off one unit</b> at that quantity — not the price
 * itself. The first tier is the base: {@code 0} off, i.e. the variant's own price, and its
 * {@code minQuantity} is the supplier's first break (25 above; 1 for most products, 4 for a family
 * like CM297 that is only sold in packs).
 *
 * <p>Every figure comes from the {@link QuantityLadder}, hence from
 * {@link com.trophy.promostandards.sync.PricingPolicy}: the same method that prices the Shopify
 * variant, so the published ladder can never drift from the published price.
 */
public record QuantityDiscountJson(String currencyCode, List<Tier> tiers) {

    /**
     * @param minQuantity    the quantity from which this discount applies
     * @param discountAmount how much is taken off one unit from that quantity up; {@code 0} on the
     *                       first tier
     */
    public record Tier(int minQuantity, BigDecimal discountAmount) {
    }

    /** Builds the payload for one ladder. */
    public static QuantityDiscountJson of(QuantityLadder ladder, String currencyCode) {
        List<Tier> tiers = new ArrayList<>();
        // The base tier carries a literal zero: nothing comes off the variant's own price there.
        tiers.add(new Tier(ladder.minimumQuantity(), BigDecimal.ZERO));
        for (QuantityLadder.Tier tier : ladder.tiers()) {
            tiers.add(new Tier(tier.quantity(), amount(tier.amountOff())));
        }
        return new QuantityDiscountJson(currencyCode == null || currencyCode.isBlank() ? "USD"
                : currencyCode.toUpperCase(Locale.ROOT), List.copyOf(tiers));
    }

    /**
     * Money as the store's own payload writes it: two decimals at most, and no trailing zeros —
     * {@code 1}, {@code 4}, {@code 19.99}. The {@code setScale} after stripping is what keeps a whole
     * amount out of scientific notation ({@code 14.00} strips to {@code 1.4E+1}, which is how Jackson
     * would then write it).
     */
    private static BigDecimal amount(BigDecimal value) {
        BigDecimal stripped = value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** @return the exact string written to the metafield. */
    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            // Records of numbers and strings; unreachable short of a broken mapper configuration.
            throw new IllegalStateException("could not serialise the quantity-discount payload", e);
        }
    }
}
