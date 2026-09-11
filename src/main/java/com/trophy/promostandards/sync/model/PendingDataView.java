package com.trophy.promostandards.sync.model;

import java.time.Instant;
import java.util.List;

/**
 * The pending-data index as the console reads it.
 *
 * @param status     {@code ready} | {@code building} (first build still running) | {@code empty}
 * @param builtAt    when the index was built; null while it never has been
 * @param count      how many ids are pending
 * @param productIds the catalog ids whose Inventory service answers nothing
 */
public record PendingDataView(String status, Instant builtAt, int count, List<String> productIds) {
}
