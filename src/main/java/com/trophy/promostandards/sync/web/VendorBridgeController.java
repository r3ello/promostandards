package com.trophy.promostandards.sync.web;

import com.trophy.promostandards.sync.VendorBridgeCatalogService;
import com.trophy.promostandards.sync.VendorBridgeSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's "From VendorBridge" tab: the store products PromoStandards no longer lists that
 * PaceSetter's website still sells. Reads live under {@code /api/catalog}, the one write under
 * {@code /api/sync}, like every other view.
 */
@RestController
public class VendorBridgeController {

    private final VendorBridgeCatalogService catalog;
    private final VendorBridgeSyncService sync;

    public VendorBridgeController(VendorBridgeCatalogService catalog, VendorBridgeSyncService sync) {
        this.catalog = catalog;
        this.sync = sync;
    }

    /** The candidates, from the Status report: costs nothing, and is as old as that report. */
    @GetMapping("/api/catalog/vendorbridge")
    public VendorBridgeCatalogService.View list() {
        return catalog.list();
    }

    /** What a sync of one product would write. Reads the store and the website; writes nothing. */
    @GetMapping("/api/catalog/vendorbridge/{productId}")
    public VendorBridgeSyncService.Plan preview(@PathVariable String productId) {
        return sync.preview(productId);
    }

    /** Applies it; 409 with nothing written when the plan is blocked. */
    @PostMapping("/api/sync/vendorbridge/{productId}")
    public VendorBridgeSyncService.Result sync(@PathVariable String productId) {
        return sync.sync(productId);
    }
}
