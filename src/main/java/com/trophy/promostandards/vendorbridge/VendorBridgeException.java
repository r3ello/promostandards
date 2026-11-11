package com.trophy.promostandards.vendorbridge;

/**
 * VendorBridge could not answer. {@code throttled} is its 503 — the vendor's site is pushing back, or
 * the bridge is busy — and means "stop asking for now", not "ask the next code".
 */
public class VendorBridgeException extends RuntimeException {

    private final boolean throttled;

    public VendorBridgeException(String message, boolean throttled) {
        super(message);
        this.throttled = throttled;
    }

    public VendorBridgeException(String message, Throwable cause) {
        super(message, cause);
        this.throttled = false;
    }

    public boolean throttled() {
        return throttled;
    }
}
