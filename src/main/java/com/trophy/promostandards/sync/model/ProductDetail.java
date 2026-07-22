package com.trophy.promostandards.sync.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Full per-product payload for the expanded (collapsed-open) table row. Unlike the import model
 * ({@link SupplierProduct}), this preserves the supplier services' detail for display: the raw
 * inventory variations, the full quantity price-break matrix, and any available charges — so the
 * console shows everything the standalone service endpoints return.
 *
 * @param productId   supplier product id
 * @param title       marketing name
 * @param description product description
 * @param vendor      brand/vendor
 * @param productType most specific category
 * @param tags        category tags
 * @param imageUrls   gallery image URLs
 * @param imported    whether the product exists in Shopify; null when Shopify isn't connected
 * @param inventory   raw inventory variation rows
 * @param pricing     price-break matrix per part (supplier prices) + computed retail
 * @param charges     available charges (setup/run/etc.)
 */
public record ProductDetail(
        String productId,
        String title,
        String description,
        String vendor,
        String productType,
        List<String> tags,
        List<String> imageUrls,
        Boolean imported,
        List<InventoryRow> inventory,
        List<PricePart> pricing,
        List<ChargeRow> charges
) {

    /**
     * @param partId      supplier part id
     * @param color       colour attribute
     * @param size        size attribute
     * @param description part description
     * @param onHand      available quantity (null if unknown)
     */
    public record InventoryRow(String partId, String color, String size, String description, Integer onHand) {
    }

    /**
     * @param partId      supplier part id
     * @param description part description
     * @param retail      retail price for the lowest break after pricing rules (null if no price)
     * @param breaks      quantity price breaks (supplier prices)
     */
    public record PricePart(String partId, String description, BigDecimal retail, List<PriceBreak> breaks) {
    }

    /**
     * @param minQuantity minimum quantity for this break
     * @param price       supplier net unit price
     * @param listPrice   supplier list price
     * @param uom         unit of measure
     */
    public record PriceBreak(int minQuantity, BigDecimal price, BigDecimal listPrice, String uom) {
    }

    /**
     * @param chargeId   supplier charge id
     * @param name       charge description
     * @param type       charge category (Setup/Run/Freight)
     * @param firstPrice the first break's unit price
     */
    public record ChargeRow(String chargeId, String name, String type, BigDecimal firstPrice) {
    }
}
