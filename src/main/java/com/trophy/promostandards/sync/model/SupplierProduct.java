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
 * @param warnings        services that did not answer, and what that costs. A supplier whose
 *                        Inventory service has no record of a product it sells, or whose Media
 *                        service faults on it, must not cost the whole import — the missing side is
 *                        left untouched in Shopify (variant stock stays as it is, because
 *                        {@code onHand} is then null; images stay as they are) and the reason is
 *                        reported here rather than thrown away
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
        List<Configuration.PartPrice> priceParts,
        List<String> warnings
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
     * @param weight         shipping weight of one unit, null when the supplier gives none. The
     *                       dimensions that come with it are deliberately not carried: they are the
     *                       same numbers the inventory row already states as its size ("9.25 X 7"),
     *                       whereas the weight exists nowhere else and is what quotes postage
     * @param weightUom      unit of {@code weight} as the supplier states it (PaceSetter: {@code LB})
     */
    public record Variant(
            String supplierPartId,
            String color,
            String size,
            String sku,
            BigDecimal supplierNet,
            BigDecimal listPrice,
            Integer onHand,
            List<String> imageUrls,
            BigDecimal weight,
            String weightUom
    ) {
    }
}
