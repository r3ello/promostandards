package com.trophy.promostandards.sync;

/**
 * The order was not sent to the supplier, and nothing was written: it is already sent, something
 * about it blocks the PO, or the mailbox is not configured. A conflict (409), not a failure — the
 * message says what to fix, and the same request works once it is fixed.
 */
public class SupplierOrderRefusedException extends RuntimeException {

    public SupplierOrderRefusedException(String message) {
        super(message);
    }
}
