package com.trophy.promostandards.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.discount.DiscountProperties;
import com.trophy.promostandards.shopify.ShopifyGraphQLClient;
import com.trophy.promostandards.shopify.ShopifyHttp;
import com.trophy.promostandards.shopify.ShopifyProperties;
import com.trophy.promostandards.shopify.ShopifyRetryProperties;
import com.trophy.promostandards.shopify.ShopifyTokenService;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.VendorBridgeCatalogService.Candidate;
import com.trophy.promostandards.sync.VendorBridgeSyncService.Plan;
import com.trophy.promostandards.sync.VendorBridgeSyncService.Result;
import com.trophy.promostandards.vendorbridge.VendorBridgeClient;
import com.trophy.promostandards.vendorbridge.VendorBridgeClient.Listing;
import com.trophy.promostandards.vendorbridge.VendorBridgeClient.Page;
import com.trophy.promostandards.vendorbridge.VendorBridgeClient.PriceTier;
import com.trophy.promostandards.vendorbridge.VendorBridgeException;
import com.trophy.promostandards.vendorbridge.VendorBridgeProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The website sync against a fake store and a fake VendorBridge: what a plan holds, what stops it, and
 * exactly what a sync writes — on the real shapes (C0610 on one page, CD929AY1G priced on its size's row).
 */
class VendorBridgeSyncServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ID = "15512256053359";
    private static final String GID = "gid://shopify/Product/" + ID;

    private final SyncProperties props = new SyncProperties("PaceSetter", "USD", "US", "en",
            SyncProperties.SkuStrategy.PART_SIZE,
            new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, false),
            new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
    private final VendorBridgeCatalogService catalog = mock(VendorBridgeCatalogService.class);
    private final List<String> operations = new ArrayList<>();
    private final Map<String, Map<?, ?>> variablesByOperation = new LinkedHashMap<>();
    /** What the bridge answers, by code. */
    private final Map<String, Page> pages = new HashMap<>();
    private final Map<String, List<PriceTier>> prices = new HashMap<>();
    private final Map<String, List<String>> images = new HashMap<>();
    private boolean bridgeDown;
    private String metafieldsRefusal;

    private static Page page(String code, String matchedBy, String url, String title) {
        return new Page(new Listing(code, "834", code, matchedBy, url, title), "<p>Blue background on clear Lucite.</p>");
    }

    private static List<PriceTier> tiers(String sku, Object... quantityThenPrice) {
        List<PriceTier> out = new ArrayList<>();
        for (int i = 0; i < quantityThenPrice.length; i += 2) {
            out.add(new PriceTier(sku, (int) quantityThenPrice[i], new BigDecimal(String.valueOf(quantityThenPrice[i + 1])), "USD"));
        }
        return out;
    }

    private static String product(String source, String ids, boolean withMedia, String... variants) {
        return """
                {"data":{"product":{"id":"%s","handle":"dynasty-collection-blue-award","title":"Dynasty Collection Blue Award",
                 "status":"DRAFT","tags":["promostandards","pacesetter-discontinued"],"descriptionHtml":"<p>old</p>",
                 "psId":null,"psIds":{"value":"%s"},"source":%s,"media":{"nodes":[%s]},
                 "variants":{"nodes":[%s]}}}}""".formatted(GID, ids.replace("\"", "\\\""),
                source == null ? "null" : "{\"value\":\"" + source + "\"}", withMedia ? "{\"id\":\"m1\"}" : "",
                String.join(",", variants));
    }

    private static String variant(String id, String sku, String price, String vendorSku) {
        return """
                {"id":"gid://shopify/ProductVariant/%s","sku":"%s","title":"Default Title","price":"%s",
                 "vendorSku":%s,"psVariantId":null}""".formatted(id, sku, price,
                vendorSku == null ? "null" : "{\"value\":\"" + vendorSku + "\"}");
    }

    private VendorBridgeSyncService service(String productJson) {
        ShopifyHttp http = (path, body, headers) -> {
            Map<?, ?> request = (Map<?, ?>) body;
            String query = String.valueOf(request.get("query"));
            String op = List.of("VendorBridgeProduct", "VariantsUpdate", "ProductContent", "ProductAddMedia", "MetafieldsSet")
                    .stream().filter(query::contains).findFirst()
                    .orElseThrow(() -> new IllegalStateException("unexpected query: " + query));
            operations.add(op);
            variablesByOperation.put(op + "#" + operations.stream().filter(op::equals).count(), (Map<?, ?>) request.get("variables"));
            try {
                return switch (op) {
                    case "VendorBridgeProduct" -> MAPPER.readTree(productJson);
                    case "VariantsUpdate" -> MAPPER.readTree("{\"data\":{\"productVariantsBulkUpdate\":{\"productVariants\":[],\"userErrors\":[]}}}");
                    case "MetafieldsSet" -> MAPPER.readTree(metafieldsRefusal != null && query(request).contains("last_sync_at")
                            && query(request).contains("\"type\":\"single_line_text_field\"")
                            ? "{\"data\":{\"metafieldsSet\":{\"metafields\":[],\"userErrors\":" + metafieldsRefusal + "}}}"
                            : "{\"data\":{\"metafieldsSet\":{\"metafields\":[],\"userErrors\":[]}}}");
                    default -> MAPPER.readTree("{\"data\":{\"productUpdate\":{\"product\":{\"id\":\"" + GID + "\"},\"userErrors\":[]}}}");
                };
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };
        ShopifyTokenService tokens = mock(ShopifyTokenService.class);
        when(tokens.getToken()).thenReturn("token");
        ShopifyGraphQLClient gql = new ShopifyGraphQLClient(http, tokens,
                new ShopifyProperties("shop.myshopify.com", "id", "secret", "whsec", "2026-04", "gid://shopify/Location/1"),
                new ShopifyRetryProperties(3, Duration.ofMillis(1), Duration.ofMillis(5)));
        VendorBridgeProperties bridgeProps = new VendorBridgeProperties("http://unused.test", "key", "pacesetter",
                Duration.ofSeconds(1), Duration.ofSeconds(1), null, Duration.ofDays(7));
        VendorBridgeClient bridge = new VendorBridgeClient(bridgeProps, MAPPER) {
            @Override
            public Optional<Page> page(String part) {
                if (bridgeDown) {
                    throw new VendorBridgeException("VendorBridge answered 503 VendorRefusing for " + part, true);
                }
                return Optional.ofNullable(pages.get(part));
            }

            @Override
            public List<PriceTier> discounts(String part) {
                return prices.getOrDefault(part, List.of());
            }

            @Override
            public List<String> images(String part) {
                return images.getOrDefault(part, List.of());
            }
        };
        return new VendorBridgeSyncService(gql, bridge, catalog, new PricingPolicy(props), props,
                new DiscountProperties(true, "trophy_discount", "discount_tiers", "json"), MAPPER);
    }

    private static String query(Map<?, ?> request) {
        try {
            return MAPPER.writeValueAsString(request.get("variables"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void candidate(int sharedWith) {
        when(catalog.candidate(ID)).thenReturn(Optional.of(new Candidate(ID, "dynasty-collection-blue-award",
                "Dynasty Collection Blue Award", "DRAFT", null, List.of("C0610"),
                "https://pacesetterawards.com/product-detail/Small-Blue-Dynasty-Award/834", "Small Blue Dynasty Award",
                "C0610", sharedWith, null)));
    }

    /** C0610 as the website has it: 197 / 183 / 169 / 157 at 1 / 3 / 6 / 25. */
    private void c0610() {
        pages.put("C0610", page("C0610", "exact", "https://pacesetterawards.com/product-detail/Small-Blue-Dynasty-Award/834",
                "Small Blue Dynasty Award"));
        prices.put("C0610", tiers("C0610", 1, "197.0", 3, "183.0", 6, "169.0", 25, "157.0"));
        images.put("C0610", List.of("https://pacesetterawards.com/Images/ProductImages/Large/C0610.jpg"));
    }

    @Test
    void theFirstSyncPlansThePriceTheLadderTheWordingAndThePhoto() {
        candidate(0);
        c0610();

        Plan plan = service(product(null, "[\"C0610\"]", false, variant("1", "PS6386", "150.00", null))).preview(ID);

        assertThat(plan.blocking()).isEmpty();
        assertThat(plan.isReady()).isTrue();
        assertThat(plan.firstSync()).isTrue();
        assertThat(plan.newTitle()).isEqualTo("Small Blue Dynasty Award");
        assertThat(plan.newDescriptionHtml()).contains("Blue background");
        assertThat(plan.imageToAdd()).endsWith("C0610.jpg");
        assertThat(plan.variants()).singleElement().satisfies(v -> {
            assertThat(v.code()).isEqualTo("C0610");
            assertThat(v.sku()).isEqualTo("PS6386");
            assertThat(v.currentPrice()).isEqualByComparingTo("150.00");
            // The website's list price, charm-priced down like every published price.
            assertThat(v.newPrice()).isEqualByComparingTo("196.99");
            assertThat(v.tiers()).extracting(t -> t.quantity() + "@" + t.unitPrice())
                    .containsExactly("3@182.99", "6@168.99", "25@156.99");
            assertThat(v.discountJson()).isEqualTo("{\"currencyCode\":\"USD\",\"tiers\":["
                    + "{\"minQuantity\":1,\"discountAmount\":0},{\"minQuantity\":3,\"discountAmount\":14},"
                    + "{\"minQuantity\":6,\"discountAmount\":28},{\"minQuantity\":25,\"discountAmount\":40}]}");
        });
        // A preview reads and nothing else.
        assertThat(operations).containsExactly("VendorBridgeProduct");
    }

    @Test
    void aSyncWritesThePriceTheCodeTheLadderAndTheMarksAndLeavesTheStatusAlone() {
        candidate(0);
        c0610();

        Result result = service(product(null, "[\"C0610\"]", false, variant("1", "PS6386", "150.00", null))).sync(ID);

        assertThat(operations).containsExactly("VendorBridgeProduct", "VariantsUpdate", "ProductContent",
                "ProductAddMedia", "MetafieldsSet", "MetafieldsSet");
        assertThat(result.variantsUpdated()).isEqualTo(1);
        assertThat(result.contentUpdated()).isTrue();
        assertThat(result.imageAdded()).isTrue();
        assertThat(result.warnings()).isEmpty();

        JsonNode variants = MAPPER.valueToTree(variablesByOperation.get("VariantsUpdate#1"));
        assertThat(variants.path("productId").asText()).isEqualTo(GID);
        JsonNode v = variants.path("variants").get(0);
        assertThat(v.path("price").asText()).isEqualTo("196.99");
        assertThat(v.path("metafields")).extracting(m -> m.path("namespace").asText() + "." + m.path("key").asText()
                + "=" + m.path("value").asText().replaceAll("\\{.*", "{…}"))
                .containsExactly("trophy_sync.vendor_sku=C0610", "custom.promo_standard_id=C0610",
                        "trophy_discount.discount_tiers={…}");

        JsonNode content = MAPPER.valueToTree(variablesByOperation.get("ProductContent#1"));
        assertThat(content.path("title").asText()).isEqualTo("Small Blue Dynasty Award");
        // Title and description only: no status in it, so a draft stays a draft.
        assertThat(content.fieldNames()).toIterable().containsExactlyInAnyOrder("id", "title", "descriptionHtml");

        JsonNode marks = MAPPER.valueToTree(variablesByOperation.get("MetafieldsSet#1")).path("metafields");
        assertThat(marks).extracting(m -> m.path("namespace").asText() + "." + m.path("key").asText() + "="
                + m.path("value").asText().replaceAll("\\{.*", "{…}"))
                .containsExactly("trophy_sync.vendor=PaceSetter", "trophy_sync.source=vendorbridge",
                        "trophy_discount.discount_tiers={…}");
        JsonNode date = MAPPER.valueToTree(variablesByOperation.get("MetafieldsSet#2")).path("metafields").get(0);
        assertThat(date.path("key").asText()).isEqualTo("last_sync_at");
        verify(catalog).markSynced(ID);
    }

    /** A later sync moves the price and the ladder and nothing else: the wording and photos are the store's. */
    @Test
    void aLaterSyncLeavesTitleDescriptionAndPhotosAlone() {
        candidate(0);
        c0610();

        VendorBridgeSyncService service = service(product("vendorbridge", "[\"C0610\"]", false,
                variant("1", "PS6386", "196.99", "C0610")));
        Result result = service.sync(ID);

        assertThat(result.plan().firstSync()).isFalse();
        assertThat(result.plan().newTitle()).isNull();
        assertThat(result.plan().imageToAdd()).isNull();
        assertThat(operations).containsExactly("VendorBridgeProduct", "VariantsUpdate", "MetafieldsSet", "MetafieldsSet");
    }

    /** "1 Year", "2 Year" and "3 Year" are three store products on one PaceSetter page: its wording is not theirs. */
    @Test
    void aPageSharedWithOtherStoreProductsKeepsTheStoresOwnWording() {
        candidate(2);
        c0610();

        Plan plan = service(product(null, "[\"C0610\"]", true, variant("1", "PS6386", "150.00", null))).preview(ID);

        assertThat(plan.isReady()).isTrue();
        assertThat(plan.newTitle()).isNull();
        assertThat(plan.notices()).anySatisfy(n -> assertThat(n).contains("shared with 2 other store product"));
        // It has a photo already: none is added.
        assertThat(plan.imageToAdd()).isNull();
    }

    /** CD929AY1G is a year and a colour of size A: the website prices the size, on the row "CD929A". */
    @Test
    void aCodeIsPricedOnTheLongestRowItStartsWith() {
        candidate(0);
        pages.put("CD929AY1G", page("CD929AY1G", "exact", "https://pacesetterawards.com/p/1", "Brushed Metallic Anniversary"));
        List<PriceTier> rows = new ArrayList<>(tiers("CD929A", 1, "70.55", 3, "63.65"));
        rows.addAll(tiers("CD929B", 1, "77.40", 3, "70.05"));
        rows.addAll(tiers("CD9", 1, "5.00"));
        prices.put("CD929AY1G", rows);

        Plan plan = service(product(null, "[\"CD929AY1G\"]", true, variant("1", "PS6059-1", "60.00", null))).preview(ID);

        assertThat(plan.variants()).singleElement().satisfies(v -> {
            assertThat(v.priceSku()).isEqualTo("CD929A");
            assertThat(v.newPrice()).isEqualByComparingTo("69.99");
        });
        assertThat(plan.notices()).anySatisfy(n -> assertThat(n).contains("priced on the website's row CD929A"));
    }

    /** Live, 2026-11-12: Blue Sail would go from 285.89 to 84.99. Allowed, but nobody should miss it. */
    @Test
    void aLargePriceMoveIsPointedOutAndASmallOneIsNot() {
        candidate(0);
        c0610();

        Plan far = service(product(null, "[\"C0610\"]", true, variant("1", "PS6386", "285.89", null))).preview(ID);
        Plan near = service(product(null, "[\"C0610\"]", true, variant("1", "PS6386", "199.00", null))).preview(ID);

        assertThat(far.isReady()).isTrue();
        assertThat(far.notices()).anySatisfy(n -> assertThat(n).contains("C0610: the price moves from 285.89 to 196.99"));
        assertThat(near.notices()).noneSatisfy(n -> assertThat(n).contains("the price moves"));
    }

    @Test
    void severalCodesEachFindTheirOwnVariantByItsMark() {
        candidate(0);
        for (String code : List.of("CD929AY1G", "CD929AY1S")) {
            pages.put(code, page(code, "exact", "https://pacesetterawards.com/p/1", "Brushed Metallic Anniversary"));
            prices.put(code, tiers("CD929A", 1, "70.55"));
        }

        Plan plan = service(product(null, "[\"CD929AY1G\",\"CD929AY1S\"]", true,
                variant("1", "PS1-G", "60.00", "CD929AY1G"), variant("2", "PS1-S", "60.00", "cd929ay1s"),
                variant("3", "PS1-X", "60.00", null))).preview(ID);

        assertThat(plan.isReady()).isTrue();
        assertThat(plan.variants()).extracting(v -> v.code() + ">" + v.sku())
                .containsExactly("CD929AY1G>PS1-G", "CD929AY1S>PS1-S");
        assertThat(plan.notices()).anySatisfy(n -> assertThat(n).contains("1 variant(s) stand for no code"));
    }

    /** One price cannot be right for two codes: the product is left for a person. */
    @Test
    void severalCodesOnOneUnmarkedVariantAreRefusedWithNothingWritten() {
        candidate(0);
        for (String code : List.of("CD929AY1G", "CD929AY1S")) {
            pages.put(code, page(code, "exact", "https://pacesetterawards.com/p/1", "Brushed Metallic Anniversary"));
            prices.put(code, tiers("CD929A", 1, "70.55"));
        }
        VendorBridgeSyncService service = service(product(null, "[\"CD929AY1G\",\"CD929AY1S\"]", true,
                variant("1", "PS6059", "60.00", null)));

        assertThatThrownBy(() -> service.sync(ID)).isInstanceOf(GroupConflictException.class)
                .hasMessageContaining("Website sync not applied")
                .hasMessageContaining("CD929AY1G has no variant of its own (1 variant(s) for 2 codes)");
        assertThat(operations).containsExactly("VendorBridgeProduct");
    }

    @Test
    void aCodeTheWebsiteHasOnlyUnderASimilarCodeIsRefused() {
        candidate(0);
        pages.put("DCCD943A", new Page(new Listing("DCCD943A", "9", "CD943A", "recoded", "https://x/9", "Other"), null));
        prices.put("DCCD943A", tiers("CD943A", 1, "50.00"));

        Plan plan = service(product(null, "[\"DCCD943A\"]", true, variant("1", "PS1", "40.00", null))).preview(ID);

        assertThat(plan.isReady()).isFalse();
        assertThat(plan.blocking()).singleElement().asString().contains("recoded match (CD943A)", "another model");
    }

    @Test
    void whatStopsAPlan() {
        // Not in the Status report as a website product, gone from the website, and no price row.
        when(catalog.candidate(ID)).thenReturn(Optional.empty());
        pages.put("C0611", page("C0611", "exact", "https://x/6", "Large Blue Dynasty Award"));

        Plan plan = service(product(null, "[\"C0610\",\"C0611\"]", true,
                variant("1", "PS1", "40.00", "C0610"), variant("2", "PS2", "40.00", "C0611"))).preview(ID);

        assertThat(plan.blocking()).anySatisfy(b -> assertThat(b).contains("Not a website product"))
                .anySatisfy(b -> assertThat(b).isEqualTo("C0610 is not on PaceSetter's website."))
                .anySatisfy(b -> assertThat(b).isEqualTo("The website has no price for C0611."));
    }

    /** A bridge that cannot answer is not "gone from the website": it blocks, and says why. */
    @Test
    void aBridgeThatCannotAnswerBlocksWithoutCallingItGone() {
        candidate(0);
        bridgeDown = true;

        Plan plan = service(product(null, "[\"C0610\"]", true, variant("1", "PS1", "40.00", null))).preview(ID);

        assertThat(plan.blocking()).singleElement().asString()
                .contains("VendorBridge could not answer for C0610", "503").doesNotContain("is not on");
    }

    /** The live store defines last_sync_at as text, another may not: the refusal names the type to use. */
    @Test
    void theSyncDateIsWrittenWithTheTypeTheStoreDefines() {
        candidate(0);
        c0610();
        metafieldsRefusal = "[{\"field\":[\"metafields\",\"0\",\"type\"],\"message\":\"Type 'single_line_text_field' "
                + "must be consistent with the definition's type: 'date_time'\"}]";

        Result result = service(product("vendorbridge", "[\"C0610\"]", true, variant("1", "PS6386", "196.99", "C0610")))
                .sync(ID);

        assertThat(result.warnings()).isEmpty();
        JsonNode retried = MAPPER.valueToTree(variablesByOperation.get("MetafieldsSet#3")).path("metafields").get(0);
        assertThat(retried.path("type").asText()).isEqualTo("date_time");
    }

    @Test
    void onlyAShopifyProductIdIsAccepted() {
        assertThatThrownBy(() -> service(product(null, "[]", true)).preview("dynasty-award"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
