package com.trophy.promostandards.common;

/**
 * PromoStandards {@code ServiceMessage} (SharedObjects). Suppliers return these in a
 * {@code ServiceMessageArray} to describe errors, warnings, or informational notices instead
 * of always raising a SOAP fault.
 *
 * @param code        numeric PromoStandards service message code
 * @param description human-readable message text
 * @param severity    severity of the message
 */
public record ServiceMessage(int code, String description, Severity severity) {

	/** PromoStandards message severity (matches the {@code Error/Warning/Information} enum). */
	public enum Severity {
		INFORMATION,
		WARNING,
		ERROR
	}

	public static ServiceMessage error(int code, String description) {
		return new ServiceMessage(code, description, Severity.ERROR);
	}
}
