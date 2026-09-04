package com.trophy.promostandards.discount.web;

import com.trophy.promostandards.discount.DiscountProperties;
import com.trophy.promostandards.discount.DiscountSyncService;
import com.trophy.promostandards.discount.DiscountSyncService.DiscountResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST facade for the quantity-break discounts.
 *
 * <p>Publishing one is a Shopify metafield write and nothing else — no third-party API, no token, no
 * campaign to reconcile — so these endpoints are just "show me the ladder" and "write it".
 */
@RestController
@RequestMapping("/api/discounts")
public class DiscountController {

    private final DiscountSyncService discounts;
    private final DiscountProperties props;

    public DiscountController(DiscountSyncService discounts, DiscountProperties props) {
        this.discounts = discounts;
        this.props = props;
    }

    /** Whether discounts are published at all, and into which metafield. */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", props.isEnabled());
        body.put("metafield", props.qualifiedName());
        body.put("namespace", props.namespace());
        body.put("key", props.key());
        body.put("type", props.type());
        return body;
    }

    /**
     * The ladder and the exact metafield value for a product, without writing anything. Works before
     * the product is imported, which makes it the way to check one first.
     */
    @GetMapping("/preview/{productId}")
    public Map<String, Object> preview(@PathVariable String productId) {
        return discounts.preview(productId);
    }

    /** Writes this product's quantity-break ladder into the metafield. */
    @PostMapping("/products/{productId}")
    public DiscountResult sync(@PathVariable String productId) {
        return discounts.sync(productId);
    }

    /** Batch version; a failure on one product is reported, not fatal to the rest. */
    @PostMapping("/products")
    public List<Map<String, Object>> syncAll(@RequestBody List<String> productIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String productId : productIds) {
            try {
                results.add(Map.of("productId", productId, "result", discounts.sync(productId)));
            } catch (RuntimeException e) {
                results.add(Map.of("productId", productId, "error", String.valueOf(e.getMessage())));
            }
        }
        return results;
    }
}
