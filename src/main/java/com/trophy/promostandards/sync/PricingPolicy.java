package com.trophy.promostandards.sync;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Turns a supplier net price into the retail price published on Shopify, per the configured
 * {@code sync.pricing.*} rules: markup, optional MAP floor, and rounding. The single place pricing
 * business rules live, so product import and scheduled price sync stay consistent.
 */
@Component
public class PricingPolicy {

    private static final BigDecimal NINETY_NINE_CENTS = new BigDecimal("0.99");

    private final SyncProperties.Pricing cfg;

    public PricingPolicy(SyncProperties props) {
        this.cfg = props.pricing();
    }

    /**
     * @param supplierNet supplier net unit price (the price break being published from)
     * @param listPrice   the supplier's own suggested retail for that same break, or null when it
     *                    publishes none
     * @return the retail price to publish, or {@code null} if no supplier price is known
     */
    public BigDecimal retailPrice(BigDecimal supplierNet, BigDecimal listPrice) {
        // The supplier's own retail price wins: it is a real figure — PaceSetter's is exactly what
        // their public product page shows. Under NINETY_NINE it is charm-priced *down* to the next
        // x.99 (208.00 -> 207.99), which is the store's house style and stays at or below the price
        // the supplier states, never above it.
        if (usesSupplierList() && listPrice != null) {
            BigDecimal price = roundsToNinetyNine() ? charmDown(listPrice) : listPrice;
            return price.setScale(2, RoundingMode.HALF_UP);
        }
        if (supplierNet == null) {
            return null;
        }
        BigDecimal price = supplierNet;
        if (cfg != null && cfg.markupPercent() != null) {
            price = supplierNet.multiply(BigDecimal.ONE.add(cfg.markupPercent().movePointLeft(2)));
        }
        if (cfg != null && cfg.mapFloor() && listPrice != null && price.compareTo(listPrice) < 0) {
            price = listPrice;
        }
        if (roundsToNinetyNine()) {
            price = charmUp(price);
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean roundsToNinetyNine() {
        return cfg != null && cfg.rounding() == SyncProperties.Pricing.Rounding.NINETY_NINE;
    }

    /** @return whether a published supplier retail price should be preferred over the markup. */
    private boolean usesSupplierList() {
        return cfg == null || cfg.strategy() == null
                || cfg.strategy() == SyncProperties.Pricing.Strategy.SUPPLIER_LIST;
    }

    /**
     * The nearest {@code x.99} at or <b>above</b> a price we computed ourselves (13.30 → 13.99).
     * A marked-up price is an internal figure, so charm pricing rounds it up and keeps the margin.
     */
    private static BigDecimal charmUp(BigDecimal price) {
        BigDecimal candidate = price.setScale(0, RoundingMode.FLOOR).add(NINETY_NINE_CENTS);
        return candidate.compareTo(price) < 0 ? candidate.add(BigDecimal.ONE) : candidate;
    }

    /**
     * The nearest {@code x.99} at or <b>below</b> a price the supplier states (208.00 → 207.99,
     * 346.10 → 345.99, 84.99 → 84.99). Rounding a stated retail price the other way would publish
     * above what the supplier itself asks. A price under 1.00 has no x.99 below it and is left alone.
     */
    private static BigDecimal charmDown(BigDecimal price) {
        BigDecimal candidate = price.setScale(0, RoundingMode.FLOOR).add(NINETY_NINE_CENTS);
        if (candidate.compareTo(price) > 0) {
            candidate = candidate.subtract(BigDecimal.ONE);
        }
        return candidate.signum() <= 0 ? price : candidate;
    }
}
