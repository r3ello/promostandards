package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.ShopifySyncService.SyncResult;
import com.trophy.promostandards.sync.model.MetafieldDefinitionView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer test for the sync endpoints: the {@link ShopifySyncService} is mocked so no Shopify call
 * is made; asserts JSON serialization and that a {@link ShopifySyncException} maps to HTTP 502.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SyncApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShopifySyncService sync;

    @MockitoBean
    private OrderSyncService orderSync;

    @MockitoBean
    private MetafieldCatalogService metafieldCatalog;

    @MockitoBean
    private SupplierOrderService supplierOrders;

    /** The console (and the server, which has no terminal) reads the automation's state here. */
    @Test
    void reportsTheScheduleState() throws Exception {
        mockMvc.perform(get("/api/sync/schedule"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.crons.inventory").exists());
    }

    /** An unknown kind is the caller's mistake, not an upstream failure. */
    @Test
    void refusesAnUnknownRefreshKind() throws Exception {
        mockMvc.perform(post("/api/sync/schedule/colours"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void importProductReturnsResult() throws Exception {
        when(sync.importProduct(eq("SAMPLE-001"), any())).thenReturn(
                new SyncResult("SAMPLE-001", "gid://shopify/Product/1", "ps-pacesetter-sample-001", false, 7, 3, java.util.List.of()));

        mockMvc.perform(post("/api/sync/products/SAMPLE-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopifyProductId").value("gid://shopify/Product/1"))
                .andExpect(jsonPath("$.variantCount").value(7))
                .andExpect(jsonPath("$.inventoryUpdated").value(3));
    }

    @Test
    void importProductPassesSelectedMetafields() throws Exception {
        when(sync.importProduct(eq("SAMPLE-001"), any())).thenReturn(
                new SyncResult("SAMPLE-001", "gid://shopify/Product/1", "ps-pacesetter-sample-001", false, 7, 3, java.util.List.of()));

        mockMvc.perform(post("/api/sync/products/SAMPLE-001")
                        .contentType("application/json")
                        .content("""
                                {"metafields":[
                                  {"namespace":"custom","key":"season","type":"single_line_text_field","value":"2026"},
                                  {"namespace":"custom","key":"supplier_brand","type":"single_line_text_field","source":"vendor"}
                                ]}"""))
                .andExpect(status().isOk());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SyncProperties.Metafield>> captor = ArgumentCaptor.forClass(List.class);
        verify(sync).importProduct(eq("SAMPLE-001"), captor.capture());
        assertThat(captor.getValue()).extracting(SyncProperties.Metafield::key)
                .containsExactly("season", "supplier_brand");
        assertThat(captor.getValue().get(0).value()).isEqualTo("2026");
        assertThat(captor.getValue().get(1).source()).isEqualTo("vendor");
    }

    @Test
    void listsMetafieldDefinitions() throws Exception {
        when(metafieldCatalog.listProductDefinitions()).thenReturn(List.of(
                new MetafieldDefinitionView("custom", "country_of_origin", "Country of origin",
                        "single_line_text_field", "Where it's made")));

        mockMvc.perform(get("/api/sync/metafield-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].namespace").value("custom"))
                .andExpect(jsonPath("$[0].key").value("country_of_origin"))
                .andExpect(jsonPath("$[0].type").value("single_line_text_field"));
    }

    /** With creation off, an id no store product carries is refused as a conflict, saying why. */
    @Test
    void importOfAnIdTheStoreDoesNotCarryIsAConflict() throws Exception {
        when(sync.importProduct(eq("CM813"), any())).thenThrow(new ProductNotInStoreException("CM813"));

        mockMvc.perform(post("/api/sync/products/CM813"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value(containsString("CM813")));
    }

    /**
     * A grouping the preview refuses is a conflict too, with the reason — here a parent the store
     * does not carry (the mocked store lists nothing) — and never reaches the sync.
     */
    @Test
    void applyingARefusedGroupingIsAConflict() throws Exception {
        mockMvc.perform(post("/api/sync/groups")
                        .contentType("application/json")
                        .content("{\"parentProductId\":\"CM291BS\",\"memberProductIds\":[\"CM291RS\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("no store product carries CM291BS")));

        verify(sync, org.mockito.Mockito.never()).importProduct(any());
    }

    /** The console asks this before offering "Add to Shopify". */
    @Test
    void reportsWhetherProductsCanBeCreated() throws Exception {
        when(sync.canCreateProducts()).thenReturn(false);

        mockMvc.perform(get("/api/sync/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createProducts").value(false));
    }

    /**
     * The console asks this before offering to send. The defaults say sending is off and no mailbox
     * is configured, which is exactly what greys the button out.
     */
    @Test
    void reportsWhetherOrdersCanBeSent() throws Exception {
        mockMvc.perform(get("/api/orders/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sendEnabled").value(false))
                .andExpect(jsonPath("$.switchedOn").value(false))
                .andExpect(jsonPath("$.smtpProblem").value(containsString("MAIL_HOST")));
    }

    /** The console's "to send" list, and the preview it opens: {@code ready} is what enables sending. */
    @Test
    void servesThePendingOrdersAndAPreview() throws Exception {
        when(supplierOrders.pending()).thenReturn(List.of(new SupplierOrderService.PendingOrder(
                "gid://shopify/Order/7291179761758", "#1046", "2026-09-22T16:48:24Z", true, "PAID",
                "Wake Forest, NC", List.of(new SupplierOrderService.LineSummary("CB35", "Optional Base", 1)),
                0, List.of())));
        when(supplierOrders.preview("7291179761758")).thenReturn(java.util.Optional.of(new SupplierOrderService.Preview(
                "gid://shopify/Order/7291179761758", "#1046", "1046", "2026-09-22T16:48:24Z", true, "PAID",
                "UNFULFILLED", null, null, null, "Standard", List.of(), List.of(),
                List.of("No PaceSetter line left to send."), List.of(), null)));

        mockMvc.perform(get("/api/orders/pacesetter-pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderName").value("#1046"))
                .andExpect(jsonPath("$[0].lines[0].partId").value("CB35"));
        mockMvc.perform(get("/api/orders/7291179761758/pacesetter-po"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poNumber").value("1046"))
                .andExpect(jsonPath("$.ready").value(false));
    }

    /** The email endpoint renders through the real template; no mailbox is configured in tests. */
    @Test
    void servesTheEmailThatWouldGoToPaceSetter() throws Exception {
        when(supplierOrders.preview("7291179761758")).thenReturn(java.util.Optional.of(new SupplierOrderService.Preview(
                "gid://shopify/Order/7291179761758", "#1046", "1046", "2026-09-22T16:48:24Z", true, "PAID",
                "UNFULFILLED", null, "5/11/2026", null, "Standard",
                List.of(new SupplierOrderService.Line("CB35", "Optional Base", null, "PS9250", 1, 1,
                        new java.math.BigDecimal("36.99"), "USD", java.util.Map.of())),
                List.of(), List.of(), List.of(), null)));

        mockMvc.perform(get("/api/orders/7291179761758/pacesetter-po/email"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("TrophyPartner.com Order P.O. # 1046"))
                .andExpect(jsonPath("$.canSend").value(false))
                .andExpect(jsonPath("$.body").value(containsString("CB35")))
                .andExpect(jsonPath("$.missing[0]").value(containsString("No recipient")));
    }

    /** An order the app will not email is a conflict, not a failure: nothing was sent, nothing written. */
    @Test
    void refusingToSendAnOrderIsAConflict() throws Exception {
        when(supplierOrders.preview("7291179761758")).thenReturn(java.util.Optional.of(new SupplierOrderService.Preview(
                "gid://shopify/Order/7291179761758", "#1046", "1046", "2026-09-22T16:48:24Z", true, "PAID",
                "UNFULFILLED", null, null, null, "Standard", List.of(), List.of(),
                List.of("Already sent to PaceSetter."), List.of(), "{}")));
        when(supplierOrders.send(any(), eq(false), any()))
                .thenThrow(new SupplierOrderRefusedException("This order cannot be sent to PaceSetter: Already sent to PaceSetter."));

        mockMvc.perform(post("/api/orders/7291179761758/pacesetter-po"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Already sent")));
    }

    @Test
    void anUnknownOrderIsNotFound() throws Exception {
        when(supplierOrders.preview("999")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/orders/999/pacesetter-po"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shopifyFailureMapsToBadGateway() throws Exception {
        when(sync.importProduct(eq("BAD"), any())).thenThrow(new ShopifySyncException("productSet userErrors: boom"));

        mockMvc.perform(post("/api/sync/products/BAD"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").value("productSet userErrors: boom"));
    }
}
