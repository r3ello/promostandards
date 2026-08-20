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
        // The supplier's own retail price wins: it is a real figure (PaceSetter's is exactly what
        // their public product page shows), and it is published untouched — rounding a stated retail
        // price to x.99 would quietly disagree with the supplier over every product.
        if (usesSupplierList() && listPrice != null) {
            return listPrice.setScale(2, RoundingMode.HALF_UP);
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
        if (cfg != null && cfg.rounding() == SyncProperties.Pricing.Rounding.NINETY_NINE) {
            price = roundToNinetyNine(price);
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    /** @return whether a published supplier retail price should be preferred over the markup. */
    private boolean usesSupplierList() {
        return cfg == null || cfg.strategy() == null
                || cfg.strategy() == SyncProperties.Pricing.Strategy.SUPPLIER_LIST;
    }

    /** Rounds to the nearest {@code x.99} at or above the input (e.g. 13.30 → 13.99, 13.00 → 12.99). */
    private static BigDecimal roundToNinetyNine(BigDecimal price) {
        BigDecimal candidate = price.setScale(0, RoundingMode.FLOOR).add(new BigDecimal("0.99"));
        if (candidate.compareTo(price) < 0) {
            candidate = candidate.add(BigDecimal.ONE);
        }
        return candidate;
    }
}
