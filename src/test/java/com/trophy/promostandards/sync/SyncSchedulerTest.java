package com.trophy.promostandards.sync;

import com.trophy.promostandards.db.SyncStateStore.Kind;
import com.trophy.promostandards.discount.DiscountSyncService;
import com.trophy.promostandards.discount.DiscountSyncService.DiscountResult;
import com.trophy.promostandards.sync.ShopifySyncService.Outcome;
import com.trophy.promostandards.sync.ShopifySyncService.RefreshResult;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static com.trophy.promostandards.sync.CatalogTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a scheduled pass is allowed to cost, and what it must not leave stale.
 *
 * <p>Both behaviours here were measured against the live store on 2026-09-09: its tagged products
 * cover 2,311 supplier ids of which PaceSetter still sells 724, and a price pushed without its
 * quantity ladder republishes discounts computed off the old price.
 */
class SyncSchedulerTest {

    private final ShopifySyncService sync = mock(ShopifySyncService.class);
    private final OrderSyncService orderSync = mock(OrderSyncService.class);
    private final CatalogSummaryService catalog = mock(CatalogSummaryService.class);
    private final DiscountSyncService discounts = mock(DiscountSyncService.class);

    private SyncScheduler scheduler() {
        SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, true),
                new SyncProperties.Schedule(true, "-", "-", "-", false), List.of(), null);
        ObjectProvider<com.trophy.promostandards.db.SyncStateStore> states = providerOf(null);
        ObjectProvider<com.trophy.promostandards.db.OrderStore> orders = providerOf(null);
        return new SyncScheduler(sync, orderSync, catalog, discounts, props, states, orders);
    }

    private static RefreshResult result(String productId, Kind kind, Outcome outcome) {
        return new RefreshResult(productId, kind, outcome, outcome == Outcome.PUSHED ? 1 : 0, null);
    }

    /**
     * The store covers ids the supplier has dropped. Reading each of them is a guaranteed failure,
     * and there are more of them than of the live ones — so they must not be read at all.
     */
    @Test
    void doesNotReadTheSupplierForIdsItNoLongerSells() {
        when(sync.listImportedProductIds()).thenReturn(List.of("CM373BS", "GONE-01", "gi307"));
        when(catalog.sellableProductIds()).thenReturn(Set.of("CM373BS", "GI307"));
        when(sync.refresh(anyString(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(call -> result(call.getArgument(0), call.getArgument(1), Outcome.UNCHANGED));

        scheduler().refreshInventory();

        verify(sync).refresh(eq("CM373BS"), eq(Kind.INVENTORY), anyBoolean(), anyBoolean());
        verify(sync).refresh(eq("gi307"), eq(Kind.INVENTORY), anyBoolean(), anyBoolean());
        verify(sync, never()).refresh(eq("GONE-01"), any(), anyBoolean(), anyBoolean());
    }

    /**
     * A supplier that cannot answer which ids it sells must not silently stop the sync: keeping a
     * wasteful pass is recoverable, skipping every product is a store that quietly goes stale.
     */
    @Test
    void refreshesEveryIdWhenTheSellableListCannotBeRead() {
        when(sync.listImportedProductIds()).thenReturn(List.of("CM373BS", "GONE-01"));
        when(catalog.sellableProductIds()).thenThrow(new IllegalStateException("supplier timeout"));
        when(sync.refresh(anyString(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(call -> result(call.getArgument(0), call.getArgument(1), Outcome.UNCHANGED));

        scheduler().refreshInventory();

        verify(sync).refresh(eq("CM373BS"), eq(Kind.INVENTORY), anyBoolean(), anyBoolean());
        verify(sync).refresh(eq("GONE-01"), eq(Kind.INVENTORY), anyBoolean(), anyBoolean());
    }

    /**
     * The published ladder is expressed as amounts off the variant's own price, so a price that
     * moved without it describes discounts off a price that no longer exists.
     */
    @Test
    void republishesTheQuantityLadderOfAProductWhosePriceMoved() {
        when(sync.listImportedProductIds()).thenReturn(List.of("CM373BS", "GI307"));
        when(catalog.sellableProductIds()).thenReturn(Set.of("CM373BS", "GI307"));
        when(sync.refresh(eq("CM373BS"), eq(Kind.PRICE), anyBoolean(), anyBoolean()))
                .thenReturn(result("CM373BS", Kind.PRICE, Outcome.PUSHED));
        when(sync.refresh(eq("GI307"), eq(Kind.PRICE), anyBoolean(), anyBoolean()))
                .thenReturn(result("GI307", Kind.PRICE, Outcome.UNCHANGED));
        when(discounts.sync("CM373BS")).thenReturn(new DiscountResult("CM373BS",
                DiscountSyncService.Outcome.PUBLISHED, null, new BigDecimal("19.99"), 25,
                List.of(), "trophy_discount.discount_tiers", true, List.of(), List.of()));

        scheduler().refreshPrices();

        verify(discounts).sync("CM373BS");
        verify(discounts, never()).sync("GI307");   // its price did not move; its ladder still holds
    }

    /** A ladder that cannot be written is a warning, never a failed price push. */
    @Test
    void aDiscountFailureDoesNotBreakThePricePass() {
        when(sync.listImportedProductIds()).thenReturn(List.of("CM373BS"));
        when(catalog.sellableProductIds()).thenReturn(Set.of("CM373BS"));
        when(sync.refresh(eq("CM373BS"), eq(Kind.PRICE), anyBoolean(), anyBoolean()))
                .thenReturn(result("CM373BS", Kind.PRICE, Outcome.PUSHED));
        when(discounts.sync("CM373BS")).thenThrow(new IllegalStateException("metafield refused"));

        scheduler().refreshPrices();   // must not propagate

        verify(discounts).sync("CM373BS");
    }

    /** Inventory does not touch prices, so it has no reason to rewrite a ladder. */
    @Test
    void doesNotTouchDiscountsOnAnInventoryPass() {
        when(sync.listImportedProductIds()).thenReturn(List.of("CM373BS"));
        when(catalog.sellableProductIds()).thenReturn(Set.of("CM373BS"));
        when(sync.refresh(eq("CM373BS"), eq(Kind.INVENTORY), anyBoolean(), anyBoolean()))
                .thenReturn(result("CM373BS", Kind.INVENTORY, Outcome.PUSHED));

        scheduler().refreshInventory();

        verify(discounts, never()).sync(anyString());
    }

    /**
     * Proving the automation must not require switching it on first: a pass can be started by hand,
     * dry, while the cron jobs are still off — which is the only way to learn what a real pass would
     * push and how long it takes before trusting it to run unattended.
     */
    @Test
    void runsAPassOnDemandEvenWithTheCronJobsOff() {
        when(sync.listImportedProductIds()).thenReturn(List.of("CM373BS"));
        when(catalog.sellableProductIds()).thenReturn(Set.of("CM373BS"));
        when(sync.refresh(anyString(), any(), anyBoolean(), anyBoolean()))
                .thenAnswer(call -> result(call.getArgument(0), call.getArgument(1), Outcome.WOULD_PUSH));
        SyncProperties off = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, true),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        SyncScheduler scheduler = new SyncScheduler(sync, orderSync, catalog, discounts, off,
                providerOf(null), providerOf(null));

        assertThat(scheduler.runNow(Kind.INVENTORY, true).enabled()).isFalse();

        // dryRun=true regardless of the configured flag: nothing may reach Shopify.
        verify(sync, timeout(5000)).refresh(eq("CM373BS"), eq(Kind.INVENTORY), eq(true), eq(false));
        scheduler.shutdown();
    }

    /**
     * Whether the automation is on, and what it just did, has to be answerable without a terminal —
     * on the server the log is inside the container, and a pass that is still walking the catalog
     * has written no summary and no database row yet.
     */
    @Test
    void reportsWhatTheLastPassDid() {
        when(sync.listImportedProductIds()).thenReturn(List.of("CM373BS", "GONE-01"));
        when(catalog.sellableProductIds()).thenReturn(Set.of("CM373BS"));
        when(sync.refresh(eq("CM373BS"), eq(Kind.INVENTORY), anyBoolean(), anyBoolean()))
                .thenReturn(result("CM373BS", Kind.INVENTORY, Outcome.PUSHED));
        SyncScheduler scheduler = scheduler();

        assertThat(scheduler.status().enabled()).isTrue();
        assertThat(scheduler.status().jobs()).isEmpty();   // nothing has run yet

        scheduler.refreshInventory();

        assertThat(scheduler.status().jobs()).singleElement().satisfies(job -> {
            assertThat(job.job()).isEqualTo("inventory");
            assertThat(job.running()).isFalse();
            assertThat(job.products()).isEqualTo(1);   // the dropped id never reached the supplier
            assertThat(job.outcomes()).containsEntry("PUSHED", 1);
        });
    }

    /**
     * A dry run has to mean the whole scheduler. The order job creates fulfillments — customers get
     * emailed tracking numbers — so if it ran, "try the automation without writing anything" would
     * be a lie on a live store.
     */
    @Test
    void aDryRunDoesNotFulfilOrders() {
        SyncProperties dry = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, true),
                new SyncProperties.Schedule(true, "-", "-", "-", true), List.of(), null);
        SyncScheduler scheduler = new SyncScheduler(sync, orderSync, catalog, discounts, dry,
                providerOf(null), providerOf(null));

        scheduler.refreshOrders();

        verify(orderSync, never()).syncOrders(any());
    }

    /** With the master switch off nothing runs — not even the supplier read that picks the ids. */
    @Test
    void doesNothingWhileTheMasterSwitchIsOff() {
        SyncProperties off = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, true),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        SyncScheduler scheduler = new SyncScheduler(sync, orderSync, catalog, discounts, off,
                providerOf(null), providerOf(null));

        scheduler.refreshInventory();
        scheduler.refreshPrices();
        scheduler.refreshOrders();

        verify(sync, never()).listImportedProductIds();
        verify(catalog, never()).sellableProductIds();
    }
}
