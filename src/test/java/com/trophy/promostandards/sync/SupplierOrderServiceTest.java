package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyHttp;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.shopify.ShopifyRetryProperties;
import com.trophy.promostandards.shopify.ShopifyTokenService;
import com.trophy.promostandards.sync.SupplierOrderService.PendingOrder;
import com.trophy.promostandards.sync.SupplierOrderService.Preview;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.trophy.promostandards.sync.SupplierOrderEmail.Recipients.CONFIGURED;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an order would send to PaceSetter, and which orders are still to send. The line shapes are the
 * dev store's real ones (2026-09-22): #1046 is CB35 on a synced variant, Easify writes the engraving
 * as line properties next to its own {@code _tpo_add_by}, and a migrated order carries a variant-less
 * "Additional charges" line.
 */
class SupplierOrderServiceTest {

    private static final ShopifyRetryProperties TEST_RETRY =
            new ShopifyRetryProperties(3, Duration.ofMillis(1), Duration.ofMillis(5));
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Map<?, ?>> variables = new ArrayList<>();
    private final List<String> operations = new ArrayList<>();
    private final SupplierOrderMailer mailer = mock(SupplierOrderMailer.class);
    private final TrophyCsv trophyCsv = mock(TrophyCsv.class);

    /** A PaceSetter line on a synced variant, with Easify's engraving. */
    private static final String CB35_ENGRAVED = """
            {"name":"Optional Base for Sculptured Lucite Star","title":"Optional Base for Sculptured Lucite Star",
             "variantTitle":null,"sku":"PS9250","quantity":2,"unfulfilledQuantity":2,
             "customAttributes":[{"key":"Engraving Style","value":"Text only"},{"key":"Line 1","value":"Coach of the Year"},
                                 {"key":"_tpo_add_by","value":"easify"}],
             "originalUnitPriceSet":{"shopMoney":{"amount":"36.99","currencyCode":"USD"}},
             "variant":{"vendorSku":{"value":"CB35"}},"product":{"psId":{"value":"CB35"}}}""";

    /** Not a PaceSetter product at all. */
    private static final String OTHER_SUPPLIER = """
            {"name":"Apple Trophy","title":"Apple Trophy","variantTitle":null,"sku":"RS104","quantity":1,
             "unfulfilledQuantity":1,"customAttributes":[],
             "originalUnitPriceSet":{"shopMoney":{"amount":"20.0","currencyCode":"USD"}},
             "variant":{"vendorSku":null},"product":{"psId":null}}""";

    /** A migrated order's charge line: no variant, no product. */
    private static final String CHARGE_LINE = """
            {"name":"Additional charges (legacy order options)","title":"Additional charges (legacy order options)",
             "variantTitle":null,"sku":null,"quantity":1,"unfulfilledQuantity":1,"customAttributes":[],
             "originalUnitPriceSet":{"shopMoney":{"amount":"15.0","currencyCode":"USD"}},
             "variant":null,"product":null}""";

    /** A PaceSetter product the sync never touched: its variant does not say which part it is. */
    private static final String UNSYNCED_PACESETTER = """
            {"name":"Walnut Single Row Challenge Coin Display","title":"Walnut Single Row Challenge Coin Display",
             "variantTitle":null,"sku":"PS9575","quantity":1,"unfulfilledQuantity":1,"customAttributes":[],
             "originalUnitPriceSet":{"shopMoney":{"amount":"167.99","currencyCode":"USD"}},
             "variant":{"vendorSku":null},"product":{"psId":{"value":"CM754A"}}}""";

    private static final String FULFILLED_PACESETTER = """
            {"name":"Teardrop Lucite w/ Glass Gemstone on Marble","title":"Teardrop Lucite w/ Glass Gemstone on Marble",
             "variantTitle":null,"sku":"PS6990","quantity":1,"unfulfilledQuantity":0,"customAttributes":[],
             "originalUnitPriceSet":{"shopMoney":{"amount":"132.99","currencyCode":"USD"}},
             "variant":{"vendorSku":{"value":"CD18"}},"product":{"psId":{"value":"CD18"}}}""";

    private static String order(String id, String name, String extra, String... lines) {
        return """
                {"id":"gid://shopify/Order/%s","name":"%s","createdAt":"2026-09-22T16:48:24Z","test":true,
                 "cancelledAt":null,"displayFinancialStatus":"PAID","displayFulfillmentStatus":"UNFULFILLED",
                 "tags":[],"note":"Use 16pt Avenir Book for line 1","sent":null,
                 "shippingAddress":{"name":"Jane Buyer","company":null,"address1":"1 Main St","address2":null,
                   "city":"Wake Forest","province":"North Carolina","provinceCode":"NC","zip":"27587",
                   "country":"United States","countryCodeV2":"US","phone":null},
                 "shippingLine":{"title":"Standard"}%s,
                 "lineItems":{"nodes":[%s]}}""".formatted(id, name, extra, String.join(",", lines));
    }

    private ShopifyHttp http(Map<String, String> byOperation) {
        return (path, body, headers) -> {
            Map<?, ?> request = (Map<?, ?>) body;
            String query = String.valueOf(request.get("query"));
            variables.add((Map<?, ?>) request.get("variables"));
            String op = byOperation.keySet().stream().filter(query::contains).findFirst()
                    .orElseThrow(() -> new IllegalStateException("unexpected query: " + query));
            operations.add(op);
            try {
                return MAPPER.readTree(byOperation.get(op));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /** Configured to send, with the mailbox filled in: the tests that refuse say why themselves. */
    private static SupplierOrderProperties sendable() {
        return new SupplierOrderProperties(true, "orders@pacesetterawards.com", "shop@trophypartner.com",
                null, "orders@trophypartner.com", null, "Purchase Order {{poNumber}}", null, "TP-4412",
                "Matt Gunn", "Dana", "UPS #4E4W93");
    }

    private SupplierOrderService service(ShopifyHttp http) {
        return service(http, sendable());
    }

    private SupplierOrderService service(ShopifyHttp http, SupplierOrderProperties props) {
        ShopifyProperties shopify = new ShopifyProperties("shop.myshopify.com", "id", "secret",
                "whsec", "2026-04", "gid://shopify/Location/1");
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        ShopifyGraphQLClient gql = new ShopifyGraphQLClient(http, tokens, shopify, TEST_RETRY);
        return new SupplierOrderService(gql,
                new SupplierOrderEmail(props, new org.springframework.core.io.DefaultResourceLoader(), mailer),
                mailer, props, trophyCsv);
    }

    private Preview preview(String orderJson) {
        return service(http(Map.of("SupplierOrderById", "{\"data\":{\"order\":" + orderJson + "}}")))
                .preview("7291179761758").orElseThrow();
    }

    @Test
    void previewsOnlyThePaceSetterLinesWithTheirEngraving() {
        Preview p = preview(order("7291179761758", "#1046", "", CB35_ENGRAVED, OTHER_SUPPLIER, CHARGE_LINE));

        assertThat(variables).singleElement()
                .satisfies(v -> assertThat(v.get("id")).isEqualTo("gid://shopify/Order/7291179761758"));
        assertThat(p.poNumber()).isEqualTo("1046");
        assertThat(p.lines()).singleElement().satisfies(l -> {
            assertThat(l.partId()).isEqualTo("CB35");
            assertThat(l.quantity()).isEqualTo(2);
            assertThat(l.unitPrice()).isEqualByComparingTo(new BigDecimal("36.99"));
            // The shopper's text, in the order the store shows it; Easify's own marker is not part of it.
            assertThat(l.personalization()).containsExactly(
                    Map.entry("Engraving Style", "Text only"), Map.entry("Line 1", "Coach of the Year"));
        });
        assertThat(p.excluded()).extracting(SupplierOrderService.ExcludedLine::sku)
                .containsExactly("RS104", null);
        assertThat(p.shipTo().city()).isEqualTo("Wake Forest");
        assertThat(p.note()).isEqualTo("Use 16pt Avenir Book for line 1");
        assertThat(p.blocking()).isEmpty();
        assertThat(p.isReady()).isTrue();
        // This fixture's note carries no date, which is worth saying and does not block anything.
        assertThat(p.notices()).containsExactly("Test order.",
                "No date the customer needs it by: the PO will not ask for one.");
    }

    /**
     * GM828 as order #1049 carries it (read live 2026-09-27): Trophy Options' cart transform leaves the
     * line's own properties empty and puts everything on its {@code lineItemGroup} — the visible copies
     * of piece 1, the browser's fake path of the logo upload, and {@code _trophy_items}.
     */
    private static String gm828(String trophyItems) throws Exception {
        return gm828(trophyItems, "gid://shopify/LineItemGroup/32487506014");
    }

    private static String gm828(String trophyItems, String groupId) throws Exception {
        return """
                {"name":"Red/Black Spiral Teardrop Art Glass","title":"Red/Black Spiral Teardrop Art Glass",
                 "variantTitle":null,"sku":"PS9349","quantity":3,"unfulfilledQuantity":3,"customAttributes":[],
                 "lineItemGroup":{"id":"%s","customAttributes":[
                   {"key":"Setup Fee","value":"Yes"},{"key":"_design_configuration","value":"Custom Design"},
                   {"key":"Engraving Logo","value":"C:\\\\fakepath\\\\blippi.jpg"},
                   {"key":"Engraving - Line 1","value":"textline1"},{"key":"Engraving - Line 1 Font","value":"Open Sans"},
                   {"key":"_trophy_surcharge","value":"0.50"},
                   {"key":"_trophy_items","value":%s}]},
                 "originalUnitPriceSet":{"shopMoney":{"amount":"51.00","currencyCode":"USD"}},
                 "variant":{"vendorSku":{"value":"GM828"}},"product":{"psId":{"value":"GM828"}}}"""
                .formatted(groupId, MAPPER.writeValueAsString(trophyItems));
    }

    @Test
    void readsTheCustomizersRecordOffTheLineItemGroup() throws Exception {
        Preview p = preview(order("7300090560606", "#1049", "", gm828(TrophyItemTest.ORDER_1049)));

        assertThat(p.lines()).singleElement().satisfies(l -> {
            // The record is the whole engraving: piece 1's visible copies and the fake logo path stay out.
            assertThat(l.personalization()).isEmpty();
            assertThat(l.customization().pieces()).hasSize(3);
            assertThat(l.customization().artwork()).containsKeys("logo", "preview");
        });
        assertThat(p.blocking()).isEmpty();
        assertThat(p.notices()).noneMatch(n -> n.contains("no text to engrave"));
    }

    private static String sentMark(String recordJson) throws Exception {
        return ",\"tags\":[\"pacesetter-enviado\"],\"sent\":"
                + (recordJson == null ? "null" : "{\"value\":" + MAPPER.writeValueAsString(recordJson) + "}");
    }

    /** Sent orders stay visible: newest send first, with when and to whom, and what went even once fulfilled. */
    @Test
    void listsTheSentOrdersNewestSendFirst() throws Exception {
        String older = order("1", "#1047", sentMark("""
                {"po":"1047","sentAt":"2026-09-26T10:00:00Z","to":"orders@pacesetterawards.com","lines":[{"partId":"CB35","quantity":2}]}"""),
                FULFILLED_PACESETTER);
        String newer = order("2", "#1049", sentMark("""
                {"po":"1049","sentAt":"2026-09-27T12:30:00Z","to":"test@example.com","lines":[{"partId":"GM828","quantity":3}]}"""),
                CB35_ENGRAVED);
        String byHand = order("3", "#1048", sentMark(null), CB35_ENGRAVED);
        SupplierOrderService service = service(http(Map.of("SupplierSentOrders",
                "{\"data\":{\"orders\":{\"nodes\":[" + older + "," + byHand + "," + newer + "]}}}")));

        List<SupplierOrderService.PendingOrder> sent = service.sent();

        assertThat(sent).extracting(SupplierOrderService.PendingOrder::orderName)
                .containsExactly("#1049", "#1047", "#1048");
        assertThat(sent.get(0).sentAt()).isEqualTo("2026-09-27T12:30:00Z");
        assertThat(sent.get(0).sentTo()).isEqualTo("test@example.com");
        // #1047's line is fulfilled by now, so the preview has no line; the record says what went.
        assertThat(sent.get(1).lines()).singleElement().satisfies(l -> {
            assertThat(l.partId()).isEqualTo("CB35");
            assertThat(l.quantity()).isEqualTo(2);
        });
        assertThat(sent.get(2).sentAt()).isNull();   // tagged by hand: no record of when
        assertThat(operations).containsExactly("SupplierSentOrders");
    }

    @Test
    void theSentSearchIsTheMarkThePendingSearchExcludes() {
        assertThat(ShopifyGraphQL.SUPPLIER_SENT_ORDERS).contains("tag:" + SupplierOrderService.SENT_TAG);
    }

    /** Two component lines of one bundle: the shopper configured one product, so it is engraved once. */
    @Test
    void readsAGroupsRecordWithItsFirstLineOnly() throws Exception {
        Preview p = preview(order("7300090560606", "#1049", "",
                gm828(TrophyItemTest.ORDER_1049), gm828(TrophyItemTest.ORDER_1049)));

        assertThat(p.lines()).hasSize(2);
        assertThat(p.lines().get(0).customization()).isNotNull();
        assertThat(p.lines().get(1).customization()).isNull();
    }

    /** Without the record, the group's visible properties are the text to engrave, as with Easify's. */
    @Test
    void fallsBackToTheGroupsVisiblePropertiesWithoutTheRecord() throws Exception {
        String noRecord = gm828(TrophyItemTest.ORDER_1049).replace("\"_trophy_items\"", "\"_not_the_record\"");
        Preview p = preview(order("7300090560606", "#1049", "", noRecord));

        assertThat(p.lines().get(0).customization()).isNull();
        assertThat(p.lines().get(0).personalization()).containsKey("Engraving - Line 1");
    }

    /** Unreadable engraving would reach PaceSetter as a blank piece: the order stops instead. */
    @Test
    void anUnreadableRecordBlocksTheOrder() throws Exception {
        Preview p = preview(order("7300090560606", "#1049", "", gm828("{\"items\":[")));

        assertThat(p.blocking()).singleElement().asString().contains("_trophy_item", "could not be read");
    }

    /** #1053: past a size Trophy Options uploads the shopper's spreadsheet and records only its link. */
    private static final String LARGE_ORDER_RECORD = """
            {"csv":"https://cdn.shopify.com/s/files/1/0655/6231/2798/files/trophy-items-engraving_template-1790705634936.csv?v=1790705636","count":3}""";

    @Test
    void readsTheSpreadsheetOfALargeOrderAsItsPieces() throws Exception {
        when(trophyCsv.fetch(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn("\uFEFFLine 1,Line 2\r\nAnn,Coach\r\nBob,\r\n\"Cruz, Jr.\",Captain\r\n");
        Preview p = preview(order("7302088687710", "#1053", "", gm828(LARGE_ORDER_RECORD)));

        assertThat(p.lines()).singleElement().satisfies(l -> {
            assertThat(l.customization().pieces()).hasSize(3);
            assertThat(l.customization().pieces().get(2).texts()).extracting(TrophyItem.Text::text)
                    .containsExactly("Cruz, Jr.", "Captain");
            assertThat(l.customization().artwork()).isEmpty();   // the link is the pieces, not artwork
        });
        assertThat(p.blocking()).isEmpty();
    }

    /** A spreadsheet that cannot be had is an order whose engraving is unknown: it stops. */
    @Test
    void aSpreadsheetThatCannotBeDownloadedBlocksTheOrder() throws Exception {
        when(trophyCsv.fetch(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalArgumentException("its CSV could not be downloaded (HTTP 404)"));
        Preview p = preview(order("7302088687710", "#1053", "", gm828(LARGE_ORDER_RECORD)));

        assertThat(p.blocking()).singleElement().asString().contains("could not be read", "HTTP 404");
    }

    @Test
    void saysWhenThePiecesDoNotMatchTheQuantity() throws Exception {
        String twoPieces = TrophyItemTest.ORDER_1049.replaceFirst(",\\s*\\{\"item\":3.*\\}\\]", "]");
        Preview p = preview(order("7300090560606", "#1049", "", gm828(twoPieces)));

        assertThat(p.notices()).contains("Red/Black Spiral Teardrop Art Glass: 2 engraved piece(s) for 3 ordered.");
    }

    /** Ordering the wrong item, or none, is worse than stopping: the line blocks, it is not dropped. */
    @Test
    void blocksAPaceSetterLineThatDoesNotSayWhichPartItIs() {
        Preview p = preview(order("1", "#1047", "", UNSYNCED_PACESETTER));

        assertThat(p.lines()).singleElement().satisfies(l -> assertThat(l.partId()).isNull());
        assertThat(p.blocking()).singleElement().asString().contains("trophy_sync.vendor_sku");
        assertThat(p.isReady()).isFalse();
    }

    @Test
    void blocksAnOrderAlreadySentByEitherMark() {
        Preview byMetafield = preview(order("1", "#1048", ",\"sent\":{\"value\":\"{\\\"po\\\":\\\"1048\\\"}\"}",
                CB35_ENGRAVED).replace("\"sent\":null,", ""));
        Preview byTag = preview(order("1", "#1049", "", CB35_ENGRAVED)
                .replace("\"tags\":[]", "\"tags\":[\"pacesetter-enviado\"]"));

        assertThat(byMetafield.sent()).isEqualTo("{\"po\":\"1048\"}");
        assertThat(byMetafield.blocking()).containsExactly("Already sent to PaceSetter.");
        assertThat(byTag.blocking()).containsExactly("Already sent to PaceSetter.");
    }

    @Test
    void asksOnlyForWhatIsLeftToFulfil() {
        Preview p = preview(order("1", "#1050", "", CB35_ENGRAVED.replace("\"unfulfilledQuantity\":2", "\"unfulfilledQuantity\":1"),
                FULFILLED_PACESETTER));

        assertThat(p.lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualTo(1);
            assertThat(l.orderedQuantity()).isEqualTo(2);
        });
        assertThat(p.excluded()).singleElement()
                .satisfies(x -> assertThat(x.reason()).isEqualTo("already fulfilled"));
        assertThat(p.notices()).anyMatch(n -> n.contains("1 of 2 already fulfilled"));
    }

    @Test
    void anUnknownOrderIsEmpty() {
        SupplierOrderService service = service(http(Map.of("SupplierOrderById", "{\"data\":{\"order\":null}}")));
        assertThat(service.preview("123")).isEmpty();
    }

    @Test
    void takesANumericIdOrAGidAndNothingElse() {
        assertThat(SupplierOrderService.orderGid("7291179761758")).isEqualTo("gid://shopify/Order/7291179761758");
        assertThat(SupplierOrderService.orderGid("gid://shopify/Order/42")).isEqualTo("gid://shopify/Order/42");
        assertThatThrownBy(() -> SupplierOrderService.orderGid("#1046")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupplierOrderService.orderGid("1 OR 1")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The list keeps the orders with a PaceSetter line left to send, across pages: not another
     * supplier's order, not one the search could not tell was sent (metafield only), not a cancelled one.
     */
    @Test
    void listsTheOrdersStillToSendAcrossPages() {
        String sentByMetafield = order("3", "#1003", "", CB35_ENGRAVED)
                .replace("\"sent\":null", "\"sent\":{\"value\":\"{}\"}");
        String cancelled = order("4", "#1004", "", CB35_ENGRAVED)
                .replace("\"cancelledAt\":null", "\"cancelledAt\":\"2026-09-22T17:00:00Z\"");
        String page1 = """
                {"data":{"orders":{"pageInfo":{"hasNextPage":true,"endCursor":"c1"},"nodes":[%s,%s,%s]}}}"""
                .formatted(order("1046", "#1046", "", CB35_ENGRAVED, CHARGE_LINE), order("2", "#1002", "", OTHER_SUPPLIER),
                        sentByMetafield);
        String page2 = """
                {"data":{"orders":{"pageInfo":{"hasNextPage":false,"endCursor":null},"nodes":[%s,%s]}}}"""
                .formatted(cancelled, order("5", "#1005", "", UNSYNCED_PACESETTER));
        List<String> pages = new ArrayList<>(List.of(page1, page2));
        ShopifyHttp paged = (path, body, headers) -> {
            variables.add((Map<?, ?>) ((Map<?, ?>) body).get("variables"));
            try {
                return MAPPER.readTree(pages.remove(0));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        List<PendingOrder> pending = service(paged).pending();

        assertThat(pending).extracting(PendingOrder::orderName).containsExactly("#1046", "#1005");
        PendingOrder first = pending.get(0);
        assertThat(first.destination()).isEqualTo("Wake Forest, NC");
        assertThat(first.lines()).singleElement().satisfies(l -> {
            assertThat(l.partId()).isEqualTo("CB35");
            assertThat(l.quantity()).isEqualTo(2);
        });
        assertThat(first.otherLines()).isEqualTo(1);
        assertThat(first.blocking()).isEmpty();
        // Listed, so it can be fixed, but flagged: it cannot go as it is.
        assertThat(pending.get(1).blocking()).isNotEmpty();
        assertThat(variables).extracting(v -> (Object) v.get("cursor")).containsExactly(null, "c1");
    }

    private static final String MARK_OK = """
            {"data":{"metafieldsSet":{"metafields":[{"id":"gid://m/1"}],"userErrors":[]},
                     "tagsAdd":{"node":{"id":"gid://shopify/Order/1"},"userErrors":[]}}}""";

    private SupplierOrderService sendService(String orderJson, String markResponse) {
        return service(http(Map.of(
                "SupplierOrderById", "{\"data\":{\"order\":" + orderJson + "}}",
                "MetafieldsSet", markResponse,
                "TagsAdd", markResponse)));
    }

    /** The email goes first, then the marks: a mark that fails is visible, a PO sent twice is not. */
    @Test
    void emailsThePoThenMarksTheOrderAsSent() {
        SupplierOrderService service = sendService(order("1046", "#1046", "", CB35_ENGRAVED), MARK_OK);

        SupplierOrderService.SendResult result = service.send(service.preview("1046").orElseThrow(), false, CONFIGURED);

        verify(mailer).send(org.mockito.ArgumentMatchers.argThat(m ->
                "orders@pacesetterawards.com".equals(m.to()) && "Purchase Order 1046".equals(m.subject())
                        && m.body().contains("CB35")));
        assertThat(operations).containsExactly("SupplierOrderById", "MetafieldsSet", "TagsAdd");
        assertThat(result.marked()).isTrue();
        assertThat(result.markError()).isNull();
        assertThat(result.poNumber()).isEqualTo("1046");
        assertThat(result.lines()).isEqualTo(1);
        // The record says what went, so a second look does not have to guess.
        String record = String.valueOf(((List<?>) variables.get(1).get("metafields")).get(0));
        assertThat(record).contains("pacesetter_po").contains("\"po\":\"1046\"").contains("CB35");
        assertThat(String.valueOf(variables.get(2).get("tags"))).contains(SupplierOrderService.SENT_TAG);
    }

    /**
     * A PO sometimes has to go to another mailbox. What was actually used is what the record stores —
     * a PO that went somewhere else must not read as if it went to the configured address.
     */
    @Test
    void sendsWhereTheCallerAsksAndRecordsThat() {
        SupplierOrderService service = sendService(order("1046", "#1046", "", CB35_ENGRAVED), MARK_OK);

        SupplierOrderService.SendResult result = service.send(service.preview("1046").orElseThrow(), false,
                new SupplierOrderEmail.Recipients("someone@else.test", null, "hidden@trophypartner.com"));

        verify(mailer).send(org.mockito.ArgumentMatchers.argThat(m -> "someone@else.test".equals(m.to())
                // cc was not overridden, so it stays the configured one
                && "shop@trophypartner.com".equals(m.cc())
                && "hidden@trophypartner.com".equals(m.bcc())
                // and the preview still reports what the app itself is set up to send to
                && "orders@pacesetterawards.com".equals(m.defaults().to())));
        assertThat(result.to()).isEqualTo("someone@else.test");
        assertThat(String.valueOf(((List<?>) variables.get(1).get("metafields")).get(0)))
                .contains("someone@else.test").contains("hidden@trophypartner.com");
    }

    /** A mailbox that cannot send refuses the order here, before the mail server is dialled. */
    @Test
    void refusesWhenTheMailboxItselfCannotSend() {
        when(mailer.problem()).thenReturn("The SMTP server at smtp.example.com is set to authenticate, "
                + "but the mailbox credentials are empty");
        SupplierOrderService service = sendService(order("1046", "#1046", "", CB35_ENGRAVED), MARK_OK);
        Preview ready = service.preview("1046").orElseThrow();

        assertThatThrownBy(() -> service.send(ready, false, CONFIGURED))
                .isInstanceOf(SupplierOrderRefusedException.class)
                .hasMessageContaining("smtp.example.com")
                .hasMessageContaining("credentials are empty");
        org.mockito.Mockito.verify(mailer, org.mockito.Mockito.never()).send(org.mockito.ArgumentMatchers.any());
    }

    /** Nothing is emailed while anything about the order is wrong: a wrong PO cannot be recalled. */
    @Test
    void refusesToSendWhatItCannotBuild() {
        SupplierOrderService service = sendService(order("1", "#1047", "", UNSYNCED_PACESETTER), MARK_OK);
        Preview blocked = service.preview("1").orElseThrow();

        assertThatThrownBy(() -> service.send(blocked, false, CONFIGURED))
                .isInstanceOf(SupplierOrderRefusedException.class)
                .hasMessageContaining("trophy_sync.vendor_sku");
        org.mockito.Mockito.verifyNoInteractions(mailer);
    }

    @Test
    void sendsAgainOnlyWhenAskedTo() {
        SupplierOrderService service = sendService(order("1", "#1048", "", CB35_ENGRAVED)
                .replace("\"tags\":[]", "\"tags\":[\"pacesetter-enviado\"]"), MARK_OK);
        Preview sent = service.preview("1").orElseThrow();

        assertThatThrownBy(() -> service.send(sent, false, CONFIGURED))
                .isInstanceOf(SupplierOrderRefusedException.class)
                .hasMessageContaining("Already sent");
        assertThat(service.send(sent, true, CONFIGURED).marked()).isTrue();
        verify(mailer).send(org.mockito.ArgumentMatchers.any());
    }

    /** The switch is off by default, so a half-configured install cannot email a supplier by accident. */
    @Test
    void refusesWhileSendingIsOff() {
        SupplierOrderProperties off = new SupplierOrderProperties(false, "orders@pacesetterawards.com",
                null, null, "orders@trophypartner.com", null, null, null, null, null, null, null);
        SupplierOrderService service = service(http(Map.of("SupplierOrderById",
                "{\"data\":{\"order\":" + order("1", "#1049", "", CB35_ENGRAVED) + "}}")), off);
        Preview ready = service.preview("1").orElseThrow();

        assertThatThrownBy(() -> service.send(ready, false, CONFIGURED))
                .isInstanceOf(SupplierOrderRefusedException.class)
                .hasMessageContaining("orders.pacesetter.enabled");
        org.mockito.Mockito.verifyNoInteractions(mailer);
    }

    /** The email is gone: a failed mark is reported, never thrown, because a retry would send it again. */
    @Test
    void reportsAMarkThatFailedAfterTheEmailWent() {
        String refused = """
                {"data":{"metafieldsSet":{"metafields":[],"userErrors":[{"field":["metafields"],"message":"Access denied"}]},
                         "tagsAdd":{"node":null,"userErrors":[]}}}""";
        SupplierOrderService service = sendService(order("1046", "#1046", "", CB35_ENGRAVED), refused);

        SupplierOrderService.SendResult result = service.send(service.preview("1046").orElseThrow(), false, CONFIGURED);

        verify(mailer).send(org.mockito.ArgumentMatchers.any());
        assertThat(result.marked()).isFalse();
        assertThat(result.markError()).contains("metafieldsSet").contains("Access denied");
    }

    /**
     * Shopify has no "needed by" field, so the shop writes it in the note — as every migrated order
     * does. Nobody saying is a notice, not a blocker: PaceSetter quotes its own lead time then.
     */
    @Test
    void readsTheDateTheCustomerNeedsItBy() {
        Preview fromNote = preview(order("1", "#1051", "", CB35_ENGRAVED)
                .replace("Use 16pt Avenir Book for line 1",
                        "***** DATE NEEDED: 5/11/2026 *****\\nUse 16pt Avenir Book for lines 1, 4, 5"));
        assertThat(fromNote.dateNeeded()).isEqualTo("5/11/2026");

        Preview silent = preview(order("1", "#1052", "", CB35_ENGRAVED));
        assertThat(silent.dateNeeded()).isNull();
        assertThat(silent.notices()).anyMatch(n -> n.contains("No date the customer needs it by"));
    }

    /** The search excludes sent orders by this tag; the code recognises them by it. One name, both places. */
    @Test
    void theSearchExcludesTheSameTagTheServiceMarksWith() {
        assertThat(ShopifyGraphQL.SUPPLIER_PENDING_ORDERS).contains("tag_not:" + SupplierOrderService.SENT_TAG);
    }
}
