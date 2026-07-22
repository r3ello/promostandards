package com.trophy.promostandards.common;

import java.util.List;

/**
 * Raised by a PromoStandards client when a request cannot be fulfilled. Carries any
 * {@link ServiceMessage}s returned by the supplier.
 *
 * <p>A real SOAP client maps SOAP faults and {@code ServiceMessageArray} entries onto this
 * exception so callers (and {@link com.trophy.promostandards.common.web.GlobalExceptionHandler})
 * handle stub and live failures uniformly.
 */
public class PromoStandardsClientException extends RuntimeException {

	private final transient List<ServiceMessage> serviceMessages;

	public PromoStandardsClientException(String message) {
		this(message, List.of());
	}

	public PromoStandardsClientException(String message, List<ServiceMessage> serviceMessages) {
		super(message);
		this.serviceMessages = List.copyOf(serviceMessages);
	}

	public PromoStandardsClientException(String message, Throwable cause) {
		super(message, cause);
		this.serviceMessages = List.of();
	}

	public List<ServiceMessage> getServiceMessages() {
		return serviceMessages;
	}
}
