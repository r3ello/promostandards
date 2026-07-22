package com.trophy.promostandards.productdata.model;

import java.time.Instant;

/**
 * Request payloads for the Product Data Service (1.0.0). Credentials and {@code wsVersion} are
 * supplied by the service layer from configuration.
 */
public final class ProductDataRequests {

	private ProductDataRequests() {
	}

	/** Mirrors {@code GetProductRequest}. */
	public record GetProductRequest(String wsVersion, String id, String password, String localizationCountry,
			String localizationLanguage, String productId) {
	}

	/**
	 * Mirrors the 1.0.0 {@code GetProductSellableRequest}. A null {@code productId} returns all
	 * products matching {@code isSellable}; {@code isSellable} is a required filter (true = list
	 * sellable products, the usual case for product discovery).
	 */
	public record GetProductSellableRequest(String wsVersion, String id, String password, String productId,
			boolean isSellable) {
	}

	/** Mirrors {@code GetProductCloseOutRequest}. */
	public record GetProductCloseOutRequest(String wsVersion, String id, String password) {
	}

	/** Mirrors {@code GetProductDateModifiedRequest}. */
	public record GetProductDateModifiedRequest(String wsVersion, String id, String password, Instant changeTimeStamp) {
	}
}
