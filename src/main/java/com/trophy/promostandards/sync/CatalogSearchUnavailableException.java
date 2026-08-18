package com.trophy.promostandards.sync;

/**
 * Raised when a request needs the catalog mirror and persistence is switched off.
 *
 * <p>Distinct from a supplier failure (502) and from a bad request (400): nothing is broken and the
 * caller did nothing wrong — the capability simply is not enabled in this install, which is a
 * <b>503</b>. Returning an unfiltered page instead would be worse than saying so.
 */
public class CatalogSearchUnavailableException extends RuntimeException {

    public CatalogSearchUnavailableException(String message) {
        super(message);
    }
}
