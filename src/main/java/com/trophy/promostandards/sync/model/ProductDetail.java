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
 * @param decorationLocations imprint locations and the decoration methods valid at each, with their
 *                    area geometry/dimensions (from Pricing &amp; Configuration's LocationArray)
 * @param productDataMissing true when the supplier's Product Data service has no record for this id
 *                    (it is still listed as sellable, and the other services may well have data) —
 *                    the title then falls back to the product id
 * @param warnings    human-readable note per service that could not be read, so a partial answer is
 *                    never mistaken for "the supplier has nothing"
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
        List<ChargeRow> charges,
        boolean productDataMissing,
        List<String> warnings,
        List<DecorationLocation> decorationLocations
) {

    /** @return a copy carrying a freshly-read {@code imported} flag (the rest is cacheable). */
    public ProductDetail withImported(Boolean imported) {
        return new ProductDetail(productId, title, description, vendor, productType, tags, imageUrls,
                imported, inventory, pricing, charges, productDataMissing, warnings, decorationLocations);
    }

    /**
     * One imprint location and what can be decorated there. The supplier's structured answer to
     * "where and how big can this be printed" — the data behind their PDF spec sheets.
     *
     * @param locationId   supplier location id
     * @param name         location name (e.g. "Front Center")
     * @param isDefault    the supplier's default location
     * @param included     decorations included in the price here
     * @param minDecoration minimum decorations orderable here
     * @param maxDecoration maximum decorations orderable here
     * @param decorations  the methods valid here, each with its imprint area
     */
    public record DecorationLocation(int locationId, String name, boolean isDefault, int included,
                                     int minDecoration, int maxDecoration,
                                     List<DecorationArea> decorations) {
    }

    /**
     * A decoration method and its imprint area. Rectangular areas carry height/width, circular ones
     * carry a diameter; {@code uom} is what those numbers are in (Inches, Stitches, Colors, …).
     *
     * @param decorationId supplier decoration id
     * @param name         method name (e.g. "Laser Engrave")
     * @param geometry     {@code Circle}, {@code Rectangular} or {@code Other}
     * @param height       area height, or null
     * @param width        area width, or null
     * @param diameter     area diameter, or null
     * @param uom          unit the dimensions are expressed in
     * @param isDefault    the supplier's default method for this location
     */
    public record DecorationArea(int decorationId, String name, String geometry, BigDecimal height,
                                 BigDecimal width, BigDecimal diameter, String uom, boolean isDefault) {
    }

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
