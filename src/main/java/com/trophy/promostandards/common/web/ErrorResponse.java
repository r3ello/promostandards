package com.trophy.promostandards.common.web;

import com.trophy.promostandards.common.ServiceMessage;

import java.time.Instant;
import java.util.List;

/**
 * Error body returned by the REST API.
 *
 * @param timestamp       when the error was produced
 * @param status          HTTP status code
 * @param message         summary message
 * @param serviceMessages PromoStandards service messages, when the failure originated upstream
 */
public record ErrorResponse(Instant timestamp, int status, String message, List<ServiceMessage> serviceMessages) {

	public static ErrorResponse of(int status, String message, List<ServiceMessage> serviceMessages) {
		return new ErrorResponse(Instant.now(), status, message, serviceMessages);
	}
}
