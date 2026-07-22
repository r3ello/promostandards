package com.trophy.promostandards.sync.model;

import com.trophy.promostandards.pricing.model.Configuration;

import java.math.BigDecimal;
import java.util.List;

/**
 * A supplier product aggregated from the PromoStandards Product Data, Pricing, Inventory, and Media
 * services into the shape needed to create/update a single Shopify product.
 *
 * <p>{@link Variant}s are the <em>union</em> of the colour/size combinations seen in Product Data
 * (parts × sizes) and in the Inventory service (per-variation rows), keyed by {@code (color, size)} —
 * suppliers express granularity inconsistently, so neither source alone is complete. {@code priceParts}
 * carries the full quantity price-break matrix (Shopify variants have a single price, so the matrix is
 * preserved as a metafield).
 *
 * @param productId       supplier product id
 * @param title           marketing name
 * @param descriptionHtml product description (HTML-safe)
 * @param vendor          brand/vendor name
 * @param productType     most specific category, mapped to Shopify productType
 * @param tags            category names, mapped to Shopify tags
 * @param variants        per (color, size) variants
 * @param imageUrls       distinct product-gallery image URLs
 * @param priceParts      raw quantity price-break matrix per part (preserved as a Shopify metafield)
 */
public record SupplierProduct(
        String productId,
        String title,
        String descriptionHtml,
        String vendor,
        String productType,
        List<String> tags,
        List<Variant> variants,
        List<String> imageUrls,
        List<Configuration.PartPrice> priceParts
) {

    /**
     * @param supplierPartId color-level supplier part id (from Product Data / Pricing)
     * @param color          color option value
     * @param size           size option value (may be null when the part has no sizes)
     * @param sku            derived variant SKU
     * @param supplierNet    supplier net unit price at the lowest quantity break (may be null)
     * @param listPrice      supplier list/MAP unit price (may be null)
     * @param onHand         available quantity (null when inventory is unknown for this variant)
     * @param imageUrls      image URLs associated with this variant's color
     */
    public record Variant(
            String supplierPartId,
            String color,
            String size,
            String sku,
            BigDecimal supplierNet,
            BigDecimal listPrice,
            Integer onHand,
            List<String> imageUrls
    ) {
    }
}
