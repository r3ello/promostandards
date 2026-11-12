package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.VendorBridgeCatalogService.Candidate;
import com.trophy.promostandards.sync.VendorBridgeCatalogService.View;
import com.trophy.promostandards.sync.model.CatalogStatusView;
import com.trophy.promostandards.sync.model.CatalogStatusView.Category;
import com.trophy.promostandards.sync.model.CatalogStatusView.Entry;
import com.trophy.promostandards.sync.model.CatalogStatusView.Web;
import com.trophy.promostandards.vendorbridge.VendorWebIndex;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which products of the Status report the "From VendorBridge" tab lists. */
class VendorBridgeCatalogServiceTest {

    private final CatalogStatusService status = mock(CatalogStatusService.class);
    private final VendorWebIndex web = mock(VendorWebIndex.class);
    private final Map<String, Web> answers = new HashMap<>();

    private static Entry entry(Category category, String adminId, String lastSync, String... codes) {
        return new Entry(category, "h-" + adminId, "Title " + adminId, "DRAFT", adminId, null, List.of(codes),
                "why", false, null, false, lastSync);
    }

    private void found(String code, String matchedBy, String url) {
        answers.put(code, new Web(true, url, "Page of " + code, code, matchedBy, matchedBy.startsWith("prefix"),
                Instant.parse("2026-11-11T08:00:00Z")));
    }

    private View list(Entry... entries) {
        when(web.enabled()).thenReturn(true);
        when(status.view()).thenReturn(new CatalogStatusView("ready", false, Instant.parse("2026-11-11T08:00:00Z"),
                Map.of(), List.of(entries), List.of(), List.of(), 0, List.of(), "https://admin/products/", answers));
        return new VendorBridgeCatalogService(status, web).list();
    }

    @Test
    void listsDiscontinuedProductsTheWebsiteHasUnderTheirExactCode() {
        found("C0610", "exact", "https://x/834");
        found("DCCD943A", "recoded", "https://x/9");
        found("CD929BY10G", "prefix", "https://x/5");
        answers.put("CM700-05", new Web(false, null, null, null, null, false, Instant.now()));
        found("CM751", "exact", "https://x/7");

        View view = list(entry(Category.DISCONTINUED, "1", null, "C0610"),
                entry(Category.DISCONTINUED, "2", null, "DCCD943A"),
                entry(Category.DISCONTINUED, "3", null, "CD929BY10G"),
                entry(Category.DISCONTINUED, "4", null, "CM700-05"),
                // Lost its data but was synced by PromoStandards: not this tab's.
                entry(Category.SYNCED_NO_DATA, "5", "2026-09-10T00:00:00Z", "CM751"));

        assertThat(view.products()).extracting(Candidate::adminId).containsExactly("1");
        assertThat(view.products().get(0).webUrl()).isEqualTo("https://x/834");
        assertThat(view.products().get(0).syncedAt()).isNull();
        // The recoded and the prefix match are counted for a review, not offered.
        assertThat(view.needReview()).isEqualTo(2);
    }

    /** Asked in the order the store lists them: the first code the website has answers for the product. */
    @Test
    void aProductIsListedByTheFirstOfItsCodesTheWebsiteHas() {
        answers.put("CD1235AY1", new Web(false, null, null, null, null, false, Instant.now()));
        found("CD1236AY1B", "exact", "https://x/12");

        View view = list(entry(Category.DISCONTINUED, "1", null, "CD1235AY1", "CD1236AY1B", "CD1236AY1G"));

        assertThat(view.products()).singleElement()
                .satisfies(c -> assertThat(c.webSku()).isEqualTo("CD1236AY1B"));
    }

    @Test
    void productsOnTheSameWebsitePageKnowTheyShareIt() {
        found("CD929AY1G", "exact", "https://x/929");
        found("CD929AY2G", "exact", "https://x/929");
        found("C0610", "exact", "https://x/834");

        View view = list(entry(Category.DISCONTINUED, "1", null, "CD929AY1G"),
                entry(Category.DISCONTINUED, "2", null, "CD929AY2G"), entry(Category.DISCONTINUED, "3", null, "C0610"));

        assertThat(view.products()).extracting(Candidate::sharedWith).containsExactly(1, 1, 0);
    }

    /** Once handed to the website sync a product stays in the tab, with when it was last synced. */
    @Test
    void aProductAlreadyKeptFromTheWebsiteStaysListedAsSynced() {
        found("C0610", "exact", "https://x/834");

        VendorBridgeCatalogService service = new VendorBridgeCatalogService(status, web);
        when(web.enabled()).thenReturn(true);
        when(status.view()).thenReturn(new CatalogStatusView("ready", false, Instant.now(), Map.of(),
                List.of(entry(Category.FROM_WEBSITE, "1", "2026-11-12T10:00:00Z", "C0610"),
                        entry(Category.DISCONTINUED, "2", null, "C0610")),
                List.of(), List.of(), 0, List.of(), null, answers));

        assertThat(service.list().products()).extracting(Candidate::syncedAt)
                .containsExactly("2026-11-12T10:00:00Z", null);
        // Synced a moment ago: the Status report will not say so until its next rebuild.
        service.markSynced("2");
        assertThat(service.candidate("2")).get().satisfies(c -> assertThat(c.syncedAt()).isNotNull());
    }
}
