package com.trophy.promostandards.discount;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.discount.DiscountSyncService.DiscountResult;
import com.trophy.promostandards.discount.DiscountSyncService.Outcome;
import com.trophy.promostandards.discount.DiscountSyncService.VariantDiscount;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.sync.CatalogService;
import com.trophy.promostandards.sync.PricingPolicy;
import com.trophy.promostandards.sync.ShopifySyncException;
import com.trophy.promostandards.sync.ShopifySyncService;
import com.trophy.promostandards.sync.SyncProperties;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.SupplierProduct;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Publishing the ladder into the metafield, driven by the real PaceSetter tables that shaped this
 * feature: GI307 (one ladder, product-wide) and EP2 / EP2PK (two ladders inside one Shopify product,
 * where a single product-wide value would be $55 wrong for one variant).
 */
class DiscountSyncServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CatalogService catalog;
    private ShopifySyncService shopifySync;

    @BeforeEach
    void setUp() {
        catalog = mock(CatalogService.class);
        shopifySync = mock(ShopifySyncService.class);
    }

    private DiscountSyncService service() {
        return service(new DiscountProperties(null, null, null, null));
    }

    private DiscountSyncService service(DiscountProperties props) {
        SyncProperties syncProps = new SyncProperties("PaceSetter", "USD", "US", "en",
                SyncProperties.SkuStrategy.PART_SIZE,
                new Pricing(Strategy.SUPPLIER_LIST, new BigDecimal("40"), Rounding.NINETY_NINE, false),
                new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
        return new DiscountSyncService(catalog, new PricingPolicy(syncProps), shopifySync, syncProps,
                props, MAPPER);
    }

    /** {minQuantity, net, list} rows, as the Pricing service returns them. */
    private void supplierPrices(String supplierId, Object[]... breaks) {
        List<Configuration.PriceBreak> rows = new ArrayList<>();
        for (Object[] b : breaks) {
            rows.add(new Configuration.PriceBreak((int) b[0], new BigDecimal(String.valueOf(b[1])),
                    new BigDecimal(String.valueOf(b[2])), "BX"));
        }
        when(catalog.aggregate(supplierId)).thenReturn(new SupplierProduct(supplierId, supplierId,
                null, null, null, List.of(), List.of(), List.of(),
                List.of(new Configuration.PartPrice(supplierId, "desc", rows)), List.of()));
    }

    /** GI307's real table: 208 / 194 / 179 at quantities 1, 3 and 6. */
    private void supplierHasGi307() {
        supplierPrices("GI307", new Object[]{1, "124.80", "208.00"},
                new Object[]{3, "116.40", "194.00"}, new Object[]{6, "107.40", "179.00"});
        when(shopifySync.supplierIdsFor("GI307")).thenReturn(List.of("GI307"));
    }

    /**
     * EP2 and EP2PK: one Shopify product, two very different prices. The figures are the live ones —
     * the app computes 345.99/294.99 and 634.99/528.99, i.e. $51 and $106 off at six.
     */
    private void supplierHasEp2Family() {
        supplierPrices("EP2", new Object[]{1, "207.66", "346.10"}, new Object[]{6, "177.51", "295.85"});
        supplierPrices("EP2PK", new Object[]{1, "381.00", "635.00"}, new Object[]{6, "317.40", "529.00"});
        when(shopifySync.supplierIdsFor("EP2")).thenReturn(List.of("EP2", "EP2PK"));
    }

    /**
     * CM373BS and its sibling CM373RV, priced as PaceSetter really prices them: 25/75/150 at
     * 19.99 / 18.99 / 15.99, and a sibling with a single price and therefore no ladder.
     */
    private void supplierHasCm373() {
        supplierPrices("CM373BS", new Object[]{25, "12.36", "19.99"},
                new Object[]{75, "11.58", "18.99"}, new Object[]{150, "10.02", "15.99"});
        supplierPrices("CM373RV", new Object[]{25, "12.36", "19.99"});
        when(shopifySync.supplierIdsFor("CM373BS")).thenReturn(List.of("CM373BS", "CM373RV"));
    }

    private void storeHasProduct(String json) {
        try {
            when(shopifySync.storeProduct(any())).thenReturn(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String GI307_PRODUCT = """
            {
              "id": "gid://shopify/Product/900",
              "handle": "p-8893-noir",
              "title": "The Noir Glass Wave On Black Glass Base",
              "variants": { "nodes": [
                { "id": "gid://shopify/ProductVariant/91", "sku": "GI307-9.25 X 7",
                  "title": "Clear Black / 9.25 X 7", "price": "207.99",
                  "psId": {"value":"GI307"} }
              ]}
            }""";

    private static final String EP2_PRODUCT = """
            {
              "id": "gid://shopify/Product/901",
              "handle": "p-8900-scroll",
              "title": "Scroll Border Photo Plaque",
              "variants": { "nodes": [
                { "id": "gid://shopify/ProductVariant/11", "sku": "EP2-10.25 X 13",
                  "title": "Brown Gold (EP2) / 10.25 X 13", "price": "345.99",
                  "psId": {"value":"EP2"} },
                { "id": "gid://shopify/ProductVariant/12", "sku": "EP2PK-10.25 X 13",
                  "title": "Brown Gold (EP2PK) / 10.25 X 13", "price": "634.99",
                  "psId": {"value":"EP2PK"} }
              ]}
            }""";

    // ---------------------------------------------------------------- one ladder

    /**
     * The client's worked example, end to end: the exact JSON, on the variant and on the product.
     * {@code discountAmount} is what comes off one unit at that quantity, and the base tier is a
     * literal 0 — the shape of the real CM373BS payload (25/75/150 -> 0, 1, 4).
     */
    @Test
    void writesOneValueOnTheVariantAndTheProduct() {
        supplierHasGi307();
        storeHasProduct(GI307_PRODUCT);

        DiscountResult result = service().sync("GI307");

        String expected = "{\"currencyCode\":\"USD\",\"tiers\":["
                + "{\"minQuantity\":1,\"discountAmount\":0},"
                + "{\"minQuantity\":3,\"discountAmount\":14},"
                + "{\"minQuantity\":6,\"discountAmount\":29}]}";
        assertThat(result.outcome()).isEqualTo(Outcome.PUBLISHED);
        assertThat(result.basePrice()).isEqualByComparingTo("207.99");
        assertThat(result.productWide()).isTrue();
        assertThat(result.metafield()).isEqualTo("trophy_discount.discount_tiers");
        assertThat(result.variants()).singleElement()
                .satisfies(v -> assertThat(v.json()).isEqualTo(expected));

        verify(shopifySync).setVariantMetafield(eq("gid://shopify/Product/900"),
                eq(List.of("gid://shopify/ProductVariant/91")), eq("trophy_discount"),
                eq("discount_tiers"), eq("json"), eq(expected));
        verify(shopifySync).setProductMetafield(eq("gid://shopify/Product/900"),
                eq("trophy_discount"), eq("discount_tiers"), eq("json"), eq(expected));
        // The badge reads the product metafield off the cached index, so it has to be re-read.
        verify(shopifySync).invalidateImportedIndex();
    }

    /**
     * A supplier product sold in several colours puts its id on every one of them — CM330's eight
     * colours all carry CM330. The ladder belongs on all of them, not on the first: keeping one
     * variant per id left seven priced wrong above the first break (2026-09-10).
     */
    @Test
    void writesTheLadderOnEveryVariantOfTheSameId() {
        supplierHasGi307();
        storeHasProduct("""
                {
                  "id": "gid://shopify/Product/902",
                  "handle": "p-8782-keychain",
                  "title": "Leatherette Bottle Opener Keychain",
                  "variants": { "nodes": [
                    { "id": "gid://shopify/ProductVariant/81", "sku": "PS10854-BS", "price": "207.99",
                      "psId": {"value":"GI307"} },
                    { "id": "gid://shopify/ProductVariant/82", "sku": "PS10854-DB", "price": "207.99",
                      "psId": {"value":"GI307"} },
                    { "id": "gid://shopify/ProductVariant/83", "sku": "PS10854-TL", "price": "207.99",
                      "psId": {"value":"GI307"} }
                  ]}
                }""");

        service().sync("GI307");

        verify(shopifySync).setVariantMetafield(eq("gid://shopify/Product/902"),
                eq(List.of("gid://shopify/ProductVariant/81", "gid://shopify/ProductVariant/82",
                        "gid://shopify/ProductVariant/83")),
                eq("trophy_discount"), eq("discount_tiers"), eq("json"), any());
    }

    /** The metafield is configuration: the consuming app names it, and nothing else knows the name. */
    @Test
    void writesIntoTheConfiguredMetafield() {
        supplierHasGi307();
        storeHasProduct(GI307_PRODUCT);

        DiscountResult result = service(new DiscountProperties(null, "trophy", "qty_breaks",
                "multi_line_text_field")).sync("GI307");

        assertThat(result.metafield()).isEqualTo("trophy.qty_breaks");
        verify(shopifySync).setProductMetafield(any(), eq("trophy"), eq("qty_breaks"),
                eq("multi_line_text_field"), any());
    }

    /** Parts priced alike stay on one value — CM297's twelve are all 109.99, from four up. */
    @Test
    void keepsOneValueWhenEveryPartSharesTheLadder() {
        supplierPrices("CM297BL", new Object[]{4, "66.00", "110.00"}, new Object[]{12, "50.85", "84.75"});
        supplierPrices("CM297RS", new Object[]{4, "66.00", "110.00"}, new Object[]{12, "50.85", "84.75"});
        when(shopifySync.supplierIdsFor("CM297BL")).thenReturn(List.of("CM297BL", "CM297RS"));
        storeHasProduct("""
                {"id":"gid://shopify/Product/902","handle":"p-x","title":"Portfolio",
                 "variants":{"nodes":[
                   {"id":"gid://shopify/ProductVariant/21","sku":"CM297BL-12 X 9.5","title":"a",
                    "price":"109.99","psId":{"value":"CM297BL"}},
                   {"id":"gid://shopify/ProductVariant/22","sku":"CM297RS-12 X 9.5","title":"b",
                    "price":"109.99","psId":{"value":"CM297RS"}}]}}""");

        DiscountResult result = service().sync("CM297BL");

        assertThat(result.variants()).singleElement().satisfies(v -> {
            assertThat(v.supplierIds()).containsExactly("CM297BL", "CM297RS");
            // Four is the minimum order, so the base tier starts there rather than at an imaginary 1.
            assertThat(v.json()).isEqualTo("{\"currencyCode\":\"USD\",\"tiers\":["
                    + "{\"minQuantity\":4,\"discountAmount\":0},"
                    + "{\"minQuantity\":12,\"discountAmount\":26}]}");
        });
        assertThat(result.productWide()).isTrue();
        verify(shopifySync).setVariantMetafield(any(),
                eq(List.of("gid://shopify/ProductVariant/21", "gid://shopify/ProductVariant/22")),
                any(), any(), any(), any());
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("minimum order quantity"));
    }

    // ---------------------------------------------------------------- two ladders

    /**
     * The case that forces per-variant values. EP2 is $51 off at six units and EP2PK $106, in one
     * Shopify product: either amount published product-wide is $55 wrong for the other variant.
     */
    @Test
    void splitsAProductWhoseVariantsArePricedDifferently() {
        supplierHasEp2Family();
        storeHasProduct(EP2_PRODUCT);

        DiscountResult result = service().sync("EP2");

        assertThat(result.variants()).hasSize(2);
        VariantDiscount ep2 = result.variants().get(0);
        VariantDiscount ep2pk = result.variants().get(1);
        assertThat(ep2.supplierIds()).containsExactly("EP2");
        assertThat(ep2.json()).isEqualTo("{\"currencyCode\":\"USD\",\"tiers\":["
                + "{\"minQuantity\":1,\"discountAmount\":0},"
                + "{\"minQuantity\":6,\"discountAmount\":51}]}");
        assertThat(ep2pk.supplierIds()).containsExactly("EP2PK");
        assertThat(ep2pk.json()).isEqualTo("{\"currencyCode\":\"USD\",\"tiers\":["
                + "{\"minQuantity\":1,\"discountAmount\":0},"
                + "{\"minQuantity\":6,\"discountAmount\":106}]}");

        // Each value lands on its own variant, and nothing claims the whole product.
        verify(shopifySync).setVariantMetafield(any(), eq(List.of("gid://shopify/ProductVariant/11")),
                any(), any(), any(), eq(ep2.json()));
        verify(shopifySync).setVariantMetafield(any(), eq(List.of("gid://shopify/ProductVariant/12")),
                any(), any(), any(), eq(ep2pk.json()));
        verify(shopifySync, never()).setProductMetafield(any(), any(), any(), any(), any());
        assertThat(result.productWide()).isFalse();
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("different ladders"));
    }

    /**
     * A product that covers an id the supplier gives no ladder for gets nothing product-wide: the
     * value would claim this ladder for a variant that has not earned it.
     */
    @Test
    void leavesTheProductAloneWhenOnlySomeIdsHaveALadder() {
        supplierPrices("EP2", new Object[]{1, "207.66", "346.10"}, new Object[]{6, "177.51", "295.85"});
        supplierPrices("EP2PK", new Object[]{1, "381.00", "635.00"});   // one price, no ladder
        when(shopifySync.supplierIdsFor("EP2")).thenReturn(List.of("EP2", "EP2PK"));
        storeHasProduct(EP2_PRODUCT);

        DiscountResult result = service().sync("EP2");

        assertThat(result.variants()).singleElement()
                .satisfies(v -> assertThat(v.variantGids()).containsExactly("gid://shopify/ProductVariant/11"));
        assertThat(result.productWide()).isFalse();
        verify(shopifySync, never()).setProductMetafield(any(), any(), any(), any(), any());
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("1 of the 2 ids"));
    }

    // ---------------------------------------------------------------- the real CM373 case

    /** CM373BS's real table (25/75/150 at 19.99/18.99/15.99) is the payload the client supplied. */
    @Test
    void matchesTheClientsRealPayload() {
        supplierHasCm373();

        Map<String, Object> preview = service().preview("CM373BS");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ladders = (List<Map<String, Object>>) preview.get("ladders");
        assertThat(String.valueOf(ladders.get(0).get("value")))
                .isEqualTo("{\"currencyCode\":\"USD\",\"tiers\":["
                        + "{\"minQuantity\":25,\"discountAmount\":0},"
                        + "{\"minQuantity\":75,\"discountAmount\":1},"
                        + "{\"minQuantity\":150,\"discountAmount\":4}]}");
    }

    /**
     * The CM373 product as the store really holds it: migrated, still on its one legacy variant, so
     * nothing carries {@code custom.promo_standard_id} — and one of its nine ids has no ladder, so
     * there is no product-wide value either. Nothing can be written, and reporting PUBLISHED for that
     * is exactly the lie this outcome exists to stop (it told us so on the live store).
     */
    @Test
    void reportsThatThereWasNowhereToWriteIt() {
        supplierHasCm373();
        storeHasProduct("""
                {"id":"gid://shopify/Product/903","handle":"p-8807-leatherette-on-steel-shot-glass",
                 "title":"Leatherette on Steel Shot Glass",
                 "variants":{"nodes":[{"id":"gid://shopify/ProductVariant/31","sku":"PS10906",
                    "title":"Default Title","price":"19.99"}]}}""");

        DiscountResult result = service().sync("CM373BS");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_WRITTEN);
        assertThat(result.reason()).contains("import the product first");
        assertThat(result.variants()).isEmpty();
        verify(shopifySync, never()).setVariantMetafield(any(), any(), any(), any(), any(), any());
        verify(shopifySync, never()).setProductMetafield(any(), any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- the quiet paths

    /** A product whose supplier gives one price has no ladder — and needs no metafield. */
    @Test
    void reportsProductsWithNothingToDiscount() {
        supplierPrices("GI586BL", new Object[]{1, "124.80", "208.00"});
        when(shopifySync.supplierIdsFor("GI586BL")).thenReturn(List.of("GI586BL"));

        DiscountResult result = service().sync("GI586BL");

        assertThat(result.outcome()).isEqualTo(Outcome.NO_DISCOUNTS);
        assertThat(result.reason()).contains("no break cheaper");
        // Not even a Shopify lookup: there is nothing to publish.
        verify(shopifySync, never()).storeProduct(any());
        verify(shopifySync, never()).setVariantMetafield(any(), any(), any(), any(), any(), any());
    }

    /**
     * What the live store actually answered when publishing CM373BS into {@code app--400283500545}:
     * the namespace belongs to the discount app, and Shopify refuses another app's write. It is a
     * userError, so it has to be reported as "not written, here is why" rather than thrown as a wall
     * of GraphQL noise over an import that otherwise worked.
     */
    @Test
    void reportsShopifyRefusingAnotherAppsNamespace() {
        supplierHasGi307();
        storeHasProduct(GI307_PRODUCT);
        org.mockito.Mockito.doThrow(new ShopifySyncException("productVariantsBulkUpdate userErrors: "
                        + "[{\"field\":[\"variants\",\"0\",\"metafields\",\"0\"],\"message\":\"Access to "
                        + "this namespace and key on Metafields for this resource type is not allowed.\"}]"))
                .when(shopifySync).setVariantMetafield(any(), any(), any(), any(), any(), any());

        DiscountResult result = service().sync("GI307");

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_WRITTEN);
        assertThat(result.reason()).contains("reserves").contains("trophy_discount.discount_tiers");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("Access to this namespace"));
        // It stopped there: no product-level write behind a refused variant write.
        verify(shopifySync, never()).setProductMetafield(any(), any(), any(), any(), any());
    }

    /** Switched off means switched off: no reads, no writes, and never a failed import. */
    @Test
    void skipsWhenDisabled() {
        DiscountResult result = service(new DiscountProperties(false, null, null, null)).sync("GI307");

        assertThat(result.outcome()).isEqualTo(Outcome.SKIPPED);
        assertThat(result.reason()).contains("disabled");
        verify(shopifySync, never()).storeProduct(any());
    }

    /** The preview is the same plan, written nowhere. */
    @Test
    void previewsWithoutWritingAnything() {
        supplierHasEp2Family();
        storeHasProduct(EP2_PRODUCT);

        Map<String, Object> preview = service().preview("EP2");

        assertThat(preview.get("outcome")).isEqualTo(Outcome.PREVIEWED);
        assertThat(preview.get("metafield")).isEqualTo("trophy_discount.discount_tiers");
        assertThat(preview.get("productWide")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> ladders = (List<Map<String, Object>>) preview.get("ladders");
        assertThat(ladders).hasSize(2);
        assertThat(ladders.get(1).get("supplierIds")).isEqualTo(List.of("EP2PK"));
        // Both values are shown, keyed by the ids they belong to.
        assertThat(String.valueOf(preview.get("payload"))).contains("EP2PK").contains("\"discountAmount\":106");
        verify(shopifySync, never()).setVariantMetafield(any(), any(), any(), any(), any(), any());
        verify(shopifySync, never()).setProductMetafield(any(), any(), any(), any(), any());
    }

    /** Previewing a product that is not in the store yet still shows what it would publish. */
    @Test
    void previewsAProductThatIsNotImportedYet() {
        supplierHasGi307();
        when(shopifySync.storeProduct(any()))
                .thenThrow(new ShopifySyncException("product GI307 has not been imported yet"));

        Map<String, Object> preview = service().preview("GI307");

        assertThat(preview.get("outcome")).isEqualTo(Outcome.PREVIEWED);
        assertThat(String.valueOf(preview.get("payload"))).contains("\"discountAmount\":14");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) preview.get("warnings");
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("not in the store yet"));
    }
}
