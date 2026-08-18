package com.trophy.promostandards.common.web;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.PromoStandardsNotFoundException;
import com.trophy.promostandards.shopify.ShopifyGraphQLException;
import com.trophy.promostandards.sync.CatalogSearchUnavailableException;
import com.trophy.promostandards.sync.ShopifySyncException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * Translates exceptions thrown by the service/client layers into HTTP responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * "The supplier has no such record" is a 404, not an upstream failure — the supplier answered
	 * fine. Declared before the broader handler below; Spring picks the most specific one.
	 */
	@ExceptionHandler(PromoStandardsNotFoundException.class)
	public ResponseEntity<ErrorResponse> handleNotFound(PromoStandardsNotFoundException ex) {
		log.debug("PromoStandards record not found: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ErrorResponse.of(HttpStatus.NOT_FOUND.value(), ex.getMessage(), ex.getServiceMessages()));
	}

	/** A PromoStandards client failure is treated as an upstream (supplier) error. */
	@ExceptionHandler(PromoStandardsClientException.class)
	public ResponseEntity<ErrorResponse> handleClientException(PromoStandardsClientException ex) {
		log.warn("PromoStandards client error: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(), ex.getMessage(), ex.getServiceMessages()));
	}

	/** Invalid request parameters map to a 400. */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(ErrorResponse.of(HttpStatus.BAD_REQUEST.value(), ex.getMessage(), List.of()));
	}

	/** A capability that needs the database, in an install running without one. */
	@ExceptionHandler(CatalogSearchUnavailableException.class)
	public ResponseEntity<ErrorResponse> handleSearchUnavailable(CatalogSearchUnavailableException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
				.body(ErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(), ex.getMessage(), List.of()));
	}

	/** A Shopify sync failure (userErrors or transport) is treated as an upstream error. */
	@ExceptionHandler({ShopifySyncException.class, ShopifyGraphQLException.class})
	public ResponseEntity<ErrorResponse> handleShopify(RuntimeException ex) {
		log.warn("Shopify sync error: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
				.body(ErrorResponse.of(HttpStatus.BAD_GATEWAY.value(), ex.getMessage(), List.of()));
	}
}
