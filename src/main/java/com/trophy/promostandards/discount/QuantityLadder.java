package com.trophy.promostandards.discount;

import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.sync.PricingPolicy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The supplier's quantity price table, turned into what the storefront actually offers: a base price
 * and an amount off per unit at each quantity.
 *
 * <p>Every figure goes through {@link PricingPolicy}, the same method that prices the Shopify
 * variant, so the ladder can never drift from the published price. Worked example — PaceSetter GI307,
 * which is the client's own reference case:
 *
 * <pre>
 *   qty 1  list 208.00 -> 207.99   (the variant price)
 *   qty 3  list 194.00 -> 193.99   -> 14.00 off
 *   qty 6  list 179.00 -> 178.99   -> 29.00 off
 * </pre>
 *
 * <p>The first break is the base even when it is not quantity 1: PaceSetter's CM297 family starts at
 * 4, which means four is the minimum you can buy, and {@link #minimumQuantity()} carries that — it
 * is the first tier's {@code minQuantity} in the published JSON.
 *
 * @see QuantityDiscountJson the metafield value built from this
 */
public record QuantityLadder(String partId, BigDecimal basePrice, int minimumQuantity,
                             List<Tier> tiers, boolean uniformAcrossParts) {

    /**
     * @param quantity  the quantity from which this tier applies
     * @param unitPrice what one unit costs at that quantity, charm-priced like every published price
     * @param amountOff {@code basePrice - unitPrice} — what the shopper saves per unit, which is
     *                  what the console shows and what decides whether a break is a discount at all
     */
    public record Tier(int quantity, BigDecimal unitPrice, BigDecimal amountOff) {
    }

    /** @return whether there is anything to publish: a product with one price break has no ladder. */
    public boolean hasDiscounts() {
        return !tiers.isEmpty();
    }

    /** @return whether the supplier sells this product only in packs (first break above 1). */
    public boolean hasMinimumQuantity() {
        return minimumQuantity > 1;
    }

    /**
     * Builds the ladder for one product.
     *
     * @param parts          the supplier's price parts (the whole quantity matrix)
     * @param preferredPartId the part to price from — the id being synced, so a grouped product uses
     *                        its canonical part rather than whichever came first
     * @param policy         the single source of published prices
     * @return the ladder, or {@code null} when the supplier priced nothing
     */
    public static QuantityLadder of(List<Configuration.PartPrice> parts, String preferredPartId,
                                    PricingPolicy policy) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        Configuration.PartPrice part = parts.stream()
                .filter(p -> p.partId() != null && p.partId().equalsIgnoreCase(preferredPartId))
                .findFirst()
                .orElse(parts.get(0));

        QuantityLadder ladder = forPart(part, policy);
        if (ladder == null) {
            return null;
        }
        // Several parts with different ladders cannot be expressed in one product-wide value; the
        // caller warns rather than silently publishing one part's discounts for all of them.
        boolean uniform = parts.stream()
                .map(p -> forPart(p, policy))
                .filter(Objects::nonNull)
                .allMatch(other -> other.tiers().equals(ladder.tiers()));

        return new QuantityLadder(ladder.partId(), ladder.basePrice(), ladder.minimumQuantity(),
                ladder.tiers(), uniform);
    }

    private static QuantityLadder forPart(Configuration.PartPrice part, PricingPolicy policy) {
        List<Configuration.PriceBreak> breaks = part.priceBreaks() == null ? List.of()
                : part.priceBreaks().stream()
                        .sorted(Comparator.comparingInt(Configuration.PriceBreak::minQuantity))
                        .toList();
        if (breaks.isEmpty()) {
            return null;
        }
        Configuration.PriceBreak first = breaks.get(0);
        BigDecimal base = policy.retailPrice(first.price(), first.listPrice());
        if (base == null) {
            return null;
        }

        List<Tier> tiers = new ArrayList<>();
        for (Configuration.PriceBreak b : breaks.subList(1, breaks.size())) {
            BigDecimal unit = policy.retailPrice(b.price(), b.listPrice());
            if (unit == null) {
                continue;
            }
            BigDecimal amountOff = base.subtract(unit);
            // A break that is not cheaper is not a discount — PaceSetter's first rows are often the
            // same price as the base, and publishing one would be a tier that saves nothing.
            if (amountOff.signum() > 0) {
                tiers.add(new Tier(b.minQuantity(), unit, amountOff));
            }
        }
        String partId = part.partId() == null ? null : part.partId().toUpperCase(Locale.ROOT);
        return new QuantityLadder(partId, base, first.minQuantity(), List.copyOf(tiers), true);
    }
}
