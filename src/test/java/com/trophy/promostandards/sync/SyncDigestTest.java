package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.SupplierProduct;
import com.trophy.promostandards.sync.model.SupplierProduct.Variant;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digest decides whether a scheduled run pushes or skips, so every failure mode here is silent
 * by nature: a digest that changes when nothing did means the scheduler saves nothing, and one that
 * stays the same when something did means Shopify quietly keeps stale values.
 */
class SyncDigestTest {

    private static PricingPolicy policy(String markup, Rounding rounding) {
        return new PricingPolicy(new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.MARKUP, new BigDecimal(markup), rounding, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null));
    }

    private static SupplierProduct product(List<Variant> variants) {
        return new SupplierProduct("SAMPLE-001", "Sample", null, null, null, List.of(),
                variants, List.of(), List.of());
    }

    private static Variant variant(String sku, String net, Integer onHand) {
        return new Variant("SAMPLE-001-RED", "Red", "S", sku,
                new BigDecimal(net), new BigDecimal("12.00"), onHand, List.of());
    }

    @Test
    void identicalDataProducesTheSameDigest() {
        SupplierProduct a = product(List.of(variant("S", "9.50", 10), variant("M", "9.50", 20)));
        SupplierProduct b = product(List.of(variant("S", "9.50", 10), variant("M", "9.50", 20)));

        assertThat(SyncDigest.forInventory(a, "loc-1")).isEqualTo(SyncDigest.forInventory(b, "loc-1"));
        assertThat(SyncDigest.forPrices(a, policy("40", Rounding.NONE), "USD"))
                .isEqualTo(SyncDigest.forPrices(b, policy("40", Rounding.NONE), "USD"));
    }

    /**
     * The supplier does not guarantee variant order between calls. Hashing the list as it arrives
     * would produce a different digest for identical data, so nothing would ever be skipped.
     */
    @Test
    void variantOrderDoesNotChangeTheDigest() {
        SupplierProduct ascending = product(List.of(variant("S", "9.50", 10), variant("M", "9.50", 20)));
        SupplierProduct descending = product(List.of(variant("M", "9.50", 20), variant("S", "9.50", 10)));

        assertThat(SyncDigest.forInventory(ascending, "loc-1"))
                .isEqualTo(SyncDigest.forInventory(descending, "loc-1"));
    }

    @Test
    void aQuantityChangeChangesTheInventoryDigest() {
        String before = SyncDigest.forInventory(product(List.of(variant("S", "9.50", 10))), "loc-1");
        String after = SyncDigest.forInventory(product(List.of(variant("S", "9.50", 11))), "loc-1");

        assertThat(after).isNotEqualTo(before);
    }

    /** Quantities are written to a location; pointing at a different one must re-push everywhere. */
    @Test
    void changingTheInventoryLocationChangesTheDigest() {
        SupplierProduct p = product(List.of(variant("S", "9.50", 10)));

        assertThat(SyncDigest.forInventory(p, "loc-2")).isNotEqualTo(SyncDigest.forInventory(p, "loc-1"));
    }

    /**
     * The one that would bite hardest in production: raising the markup changes what belongs in
     * Shopify while the supplier's own price is untouched. Hashing the supplier price would skip
     * every product and leave the old prices live, with nothing in the logs to show for it.
     */
    @Test
    void aPricingPolicyChangeMakesEveryPriceDueAgain() {
        SupplierProduct p = product(List.of(variant("S", "9.50", 10)));

        String at40 = SyncDigest.forPrices(p, policy("40", Rounding.NONE), "USD");
        String at45 = SyncDigest.forPrices(p, policy("45", Rounding.NONE), "USD");
        String at40Rounded = SyncDigest.forPrices(p, policy("40", Rounding.NINETY_NINE), "USD");

        assertThat(at45).isNotEqualTo(at40);
        assertThat(at40Rounded).isNotEqualTo(at40);
    }

    @Test
    void changingTheCurrencyChangesThePriceDigest() {
        SupplierProduct p = product(List.of(variant("S", "9.50", 10)));
        PricingPolicy pricing = policy("40", Rounding.NONE);

        assertThat(SyncDigest.forPrices(p, pricing, "CAD"))
                .isNotEqualTo(SyncDigest.forPrices(p, pricing, "USD"));
    }

    /** Inventory and prices are tracked apart: a price move must not silently satisfy an inventory run. */
    @Test
    void inventoryAndPriceDigestsAreIndependent() {
        SupplierProduct cheap = product(List.of(variant("S", "9.50", 10)));
        SupplierProduct dearer = product(List.of(variant("S", "11.00", 10)));

        assertThat(SyncDigest.forInventory(dearer, "loc-1"))
                .isEqualTo(SyncDigest.forInventory(cheap, "loc-1"));
        assertThat(SyncDigest.forPrices(dearer, policy("40", Rounding.NONE), "USD"))
                .isNotEqualTo(SyncDigest.forPrices(cheap, policy("40", Rounding.NONE), "USD"));
    }

    /** A variant with no quantity is not pushed, so it must not contribute to the inventory digest. */
    @Test
    void variantsWithoutQuantitiesDoNotAffectTheInventoryDigest() {
        SupplierProduct withNull = product(List.of(variant("S", "9.50", 10), variant("M", "9.50", null)));
        SupplierProduct without = product(List.of(variant("S", "9.50", 10)));

        assertThat(SyncDigest.forInventory(withNull, "loc-1"))
                .isEqualTo(SyncDigest.forInventory(without, "loc-1"));
    }
}
