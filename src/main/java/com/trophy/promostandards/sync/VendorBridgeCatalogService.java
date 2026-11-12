package com.trophy.promostandards.sync;

import com.trophy.promostandards.sync.model.CatalogStatusView;
import com.trophy.promostandards.sync.model.CatalogStatusView.Category;
import com.trophy.promostandards.sync.model.CatalogStatusView.Entry;
import com.trophy.promostandards.sync.model.CatalogStatusView.Web;
import com.trophy.promostandards.vendorbridge.VendorWebIndex;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The console's "From VendorBridge" tab: the store products PromoStandards no longer lists that
 * PaceSetter's <em>website</em> still sells, which are therefore kept from the website instead.
 *
 * <p>Nothing is read here: the list is the Status report's {@code Discontinued} products whose code
 * the website has, plus the ones already handed to the website sync ({@code From website}). So it
 * costs nothing, and it is as old as that report.
 *
 * <p><b>Only an exact match is a candidate.</b> A recoded or prefix match (a Digi-Color {@code DC}
 * prefix, a dropped colour suffix) may be a neighbouring model, and a sync would publish its price
 * and its wording on the wrong product; those are counted, not listed, until someone reviews them.
 */
@Service
public class VendorBridgeCatalogService {

    /** How many of a product's codes the website was asked about — {@link CatalogStatusService}'s. */
    private static final int CODES_ASKED = 3;

    private final CatalogStatusService status;
    private final VendorWebIndex web;
    /** Synced since the Status report was built, which still shows them as never synced. */
    private final Map<String, Instant> syncedSince = new ConcurrentHashMap<>();

    public VendorBridgeCatalogService(CatalogStatusService status, VendorWebIndex web) {
        this.status = status;
        this.web = web;
    }

    /**
     * @param adminId    numeric Shopify product id
     * @param codes      the PaceSetter codes the product lists
     * @param webSku     the website SKU its code matched
     * @param sharedWith how many other products of this list point at the same website page; their
     *                   title and description are that page's, so a sync keeps the store's own
     * @param syncedAt   when the website sync last ran on it, null if never
     */
    public record Candidate(String adminId, String handle, String title, String status, String imageUrl,
                            List<String> codes, String webUrl, String webTitle, String webSku, int sharedWith,
                            String syncedAt) {
    }

    /**
     * @param enabled       VendorBridge is configured
     * @param status        the Status report's: {@code ready} | {@code building} | {@code empty}
     * @param needReview    products whose code the website has only as a recoded or prefix match
     * @param adminProducts the store admin's products URL, to which {@code adminId} is appended
     */
    public record View(boolean enabled, String status, boolean building, Instant builtAt, String adminProducts,
                       List<Candidate> products, int needReview) {
    }

    public View list() {
        CatalogStatusView report = status.view();
        List<Candidate> out = new ArrayList<>();
        int needReview = 0;
        Map<String, Integer> perPage = new HashMap<>();
        List<Entry> chosen = new ArrayList<>();
        List<Web> hits = new ArrayList<>();
        for (Entry e : report.products()) {
            if (e.category() != Category.DISCONTINUED && e.category() != Category.FROM_WEBSITE) {
                continue;
            }
            Web hit = hit(report, e);
            if (hit == null) {
                continue;
            }
            if (!exact(hit) && e.category() == Category.DISCONTINUED) {
                needReview++;
                continue;
            }
            chosen.add(e);
            hits.add(hit);
            if (hit.url() != null) {
                perPage.merge(hit.url(), 1, Integer::sum);
            }
        }
        for (int i = 0; i < chosen.size(); i++) {
            Entry e = chosen.get(i);
            Web hit = hits.get(i);
            Instant since = e.adminId() == null ? null : syncedSince.get(e.adminId());
            String syncedAt = since != null ? since.toString()
                    : e.category() == Category.FROM_WEBSITE ? e.lastSyncAt() : null;
            out.add(new Candidate(e.adminId(), e.handle(), e.title(), e.status(), e.imageUrl(), e.supplierIds(),
                    hit.url(), hit.title(), hit.matchedSku(),
                    hit.url() == null ? 0 : perPage.getOrDefault(hit.url(), 1) - 1, syncedAt));
        }
        return new View(web.enabled(), report.status(), report.building(), report.builtAt(),
                report.adminProducts(), out, needReview);
    }

    /** The candidate for one store product, if it is one. */
    public Optional<Candidate> candidate(String adminId) {
        return list().products().stream().filter(c -> adminId != null && adminId.equals(c.adminId())).findFirst();
    }

    /** Called by the website sync: the Status report will not say so until its next rebuild. */
    void markSynced(String adminId) {
        if (adminId != null) {
            syncedSince.put(adminId, Instant.now());
        }
    }

    /** The first of the product's codes the website has, as the Status view picks it. */
    private static Web hit(CatalogStatusView report, Entry e) {
        return e.supplierIds().stream().limit(CODES_ASKED)
                .map(code -> report.web().get(VendorWebIndex.key(code)))
                .filter(w -> w != null && w.found()).findFirst().orElse(null);
    }

    private static boolean exact(Web hit) {
        return "exact".equals(hit.matchedBy()) || "no-punct".equals(hit.matchedBy());
    }
}
