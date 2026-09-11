package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.service.InventoryService;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An id is pending when PaceSetter's Inventory service has nothing for it: "ProductID not found"
 * (CD1268, the G099x glasses) and an empty answer mean the same thing to the store — no stock to
 * publish — and neither may fail the whole build.
 */
class PendingDataIndexTest {

    @Test
    void anIdWithoutInventoryDataIsPending() {
        ProductDataService productData = mock(ProductDataService.class);
        InventoryService inventory = mock(InventoryService.class);
        // Built first: stubbing a mock inside another when(...) is an unfinished stubbing.
        List<ProductSellable> catalog = List.of(sellable("GI307"), sellable("CD1268"), sellable("G0990"));
        when(productData.getProductSellable(null, true)).thenReturn(catalog);
        when(inventory.getInventoryLevels(eq("GI307"), any())).thenReturn(new InventoryLevels("GI307",
                List.of(new InventoryLevels.PartInventory("GI307", "Glass Wave", null, 95, "Clear Black",
                        null, null, null))));
        when(inventory.getInventoryLevels(eq("CD1268"), any()))
                .thenThrow(new PromoStandardsClientException("ProductID not found"));
        when(inventory.getInventoryLevels(eq("G0990"), any())).thenReturn(new InventoryLevels("G0990", List.of()));

        PendingDataIndex index = new PendingDataIndex(productData, inventory,
                new PendingDataProperties(true, "", Duration.ofHours(6)), new ObjectMapper(),
                mock(SyncProperties.class), new PromoStandardsProperties());

        assertThat(index.buildNow().productIds()).containsExactly("CD1268", "G0990");
    }

    private static ProductSellable sellable(String productId) {
        ProductSellable s = mock(ProductSellable.class);
        when(s.productId()).thenReturn(productId);
        return s;
    }
}
