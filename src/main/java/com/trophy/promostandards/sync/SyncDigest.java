package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Stable fingerprints of what a sync is about to push, so an unchanged product costs nothing.
 *
 * <p>Two properties make this safe to skip work on, and both are easy to get wrong:
 *
 * <ul>
 *   <li><b>It hashes the outgoing values, not the supplier's.</b> The price digest covers the final
 *       retail price after {@link PricingPolicy} — markup, MAP floor and rounding included. Hashing
 *       the supplier's net price instead would mean raising the markup changed what belongs in
 *       Shopify but not the digest, so every product would be skipped and the old prices would stay
 *       live with nothing in the logs to show for it. The inventory digest likewise includes the
 *       target location: moving locations must re-push everywhere.</li>
 *   <li><b>It is order-independent.</b> Variant order from the supplier is not guaranteed between
 *       calls, so entries are sorted by SKU and numbers are rendered canonically
 *       ({@code toPlainString()}, no locale). Without that, identical data would hash differently
 *       from one run to the next and nothing would ever be skipped.</li>
 * </ul>
 *
 * <p>A digest covers only the fields its sync actually writes: an inventory run must not be skipped
 * because a price changed, or vice versa.
 */
final class SyncDigest {

    private SyncDigest() {
    }

    /** Quantities the inventory sync would write, plus the location they would be written to. */
    static String forInventory(SupplierProduct product, String locationId) {
        List<String> entries = new ArrayList<>();
        for (Variant variant : sortedBySku(product)) {
            if (variant.onHand() != null) {
                entries.add(variant.sku() + "=" + variant.onHand());
            }
        }
        return sha256("inv|" + nullSafe(locationId) + "|" + String.join(";", entries));
    }

    /** Final retail prices the price sync would write — after the pricing policy, not before it. */
    static String forPrices(SupplierProduct product, PricingPolicy pricingPolicy, String currency) {
        List<String> entries = new ArrayList<>();
        for (Variant variant : sortedBySku(product)) {
            BigDecimal price = pricingPolicy.retailPrice(variant.supplierNet(), variant.listPrice());
            if (price != null) {
                entries.add(variant.sku() + "=" + price.toPlainString());
            }
        }
        return sha256("price|" + nullSafe(currency) + "|" + String.join(";", entries));
    }

    private static List<Variant> sortedBySku(SupplierProduct product) {
        List<Variant> variants = new ArrayList<>(product.variants());
        variants.sort(Comparator.comparing(v -> nullSafe(v.sku())));
        return variants;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String payload) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK", e);
        }
    }
}
