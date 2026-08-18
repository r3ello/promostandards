package com.trophy.promostandards.common;

import java.util.List;

/**
 * Raised when the supplier answers successfully but simply has no record for what was asked — as
 * opposed to a transport/SOAP failure, which stays a plain {@link PromoStandardsClientException}.
 *
 * <p>The distinction matters because suppliers are internally inconsistent: PaceSetter's sellable
 * catalog ({@code getProductSellable}) lists ids its Product Data service has no record of (e.g.
 * GI840), so "listed but no product" is a normal, expected state — not an upstream outage. Callers
 * aggregating several services (the catalog detail) degrade gracefully on this one and keep the
 * data the other services do return, while
 * {@link com.trophy.promostandards.common.web.GlobalExceptionHandler} maps it to <b>404</b> rather
 * than the 502 an actual supplier failure earns.
 */
public class PromoStandardsNotFoundException extends PromoStandardsClientException {

	public PromoStandardsNotFoundException(String message) {
		super(message);
	}

	public PromoStandardsNotFoundException(String message, List<ServiceMessage> serviceMessages) {
		super(message, serviceMessages);
	}
}
