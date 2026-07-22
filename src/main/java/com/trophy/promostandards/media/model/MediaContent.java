package com.trophy.promostandards.media.model;

/**
 * Mirrors a focused subset of the PromoStandards {@code MediaContent} type.
 *
 * @param productId     supplier product id
 * @param partId        supplier part id the media belongs to (may be null for product-level media)
 * @param mediaType     "Image", "Audio", "Video", or "Document"
 * @param url           location of the asset
 * @param fileName      asset file name
 * @param description   human-readable description
 * @param classTypeId   PromoStandards class type id (view/angle/usage classification)
 * @param classTypeName class type description
 * @param width         pixel width (images only; null otherwise)
 * @param height        pixel height (images only; null otherwise)
 */
public record MediaContent(String productId, String partId, String mediaType, String url, String fileName,
		String description, Integer classTypeId, String classTypeName, Integer width, Integer height) {
}
