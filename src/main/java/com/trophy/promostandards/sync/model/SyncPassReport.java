package com.trophy.promostandards.sync.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * What one scheduled inventory or price pass did, published when it finishes
 * ({@code SyncScheduler}) — what the sync report email is written from.
 *
 * @param job               {@code inventory} or {@code price}
 * @param dryRun            nothing was written: {@code changed} is what <em>would</em> have been
 * @param products          ids the pass walked
 * @param variantsWritten   variants written to Shopify
 * @param outcomes          products per outcome (PUSHED, UNCHANGED, FAILED, …)
 * @param changed           the products whose values were (or would be) pushed
 * @param failed            the products whose read or push failed, with the error
 * @param discountProblems  price passes: products whose quantity-break ladder could not be
 *                          republished after their price moved
 */
public record SyncPassReport(String job, boolean dryRun, Instant startedAt, Instant finishedAt, int products,
                             int variantsWritten, Map<String, Integer> outcomes, List<Line> changed,
                             List<Line> failed, List<Line> discountProblems) {

    /** One product: its supplier id, variants written, and the error when it failed. */
    public record Line(String productId, int variants, String error) {
    }
}
