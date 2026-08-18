package com.trophy.promostandards.db;

import java.time.Instant;
import java.util.Optional;

/**
 * Persistence port for sync bookkeeping — the one thing in this system the database genuinely owns.
 *
 * <p>It cannot be recomputed from either side: the supplier does not know what was pushed and
 * Shopify does not record where its values came from. Without it, every scheduled run re-pushes the
 * entire catalog, which is what the incremental scheduler exists to stop.
 *
 * <p>Absent unless {@code sync.persistence.enabled=true}. Callers hold it as
 * {@code ObjectProvider<SyncStateStore>}; when it is missing the scheduler falls back to pushing
 * everything, exactly as before — but it must never do that <em>because a query failed</em>, which
 * would turn a database blip into a full-catalog write storm.
 */
public interface SyncStateStore {

	/** What kind of push the state describes. Each is tracked independently. */
	enum Kind {
		INVENTORY, PRICE, IMPORT;

		public String value() {
			return name().toLowerCase(java.util.Locale.ROOT);
		}
	}

	/**
	 * @param payloadHash         digest of the values last pushed successfully, null if never
	 * @param consecutiveFailures failures since the last success, driving the retry backoff
	 * @param nextAttemptAfter    do not retry before this instant; null when there is no backoff
	 */
	record State(String payloadHash, int consecutiveFailures, Instant nextAttemptAfter) {

		/** @return true when a failing product is still inside its backoff window. */
		public boolean isBackingOff(Instant now) {
			return nextAttemptAfter != null && nextAttemptAfter.isAfter(now);
		}
	}

	Optional<State> find(String productId, Kind kind);

	/**
	 * Records a push that Shopify accepted. Clears the failure counter and backoff — writing the
	 * hash before the mutation returned would mark a failed product as synced forever.
	 */
	void recordSuccess(String productId, Kind kind, String payloadHash);

	/**
	 * Records a failed push, leaving the stored hash untouched so the product stays due, and pushing
	 * {@code nextAttemptAfter} out so one permanently broken product cannot consume every run.
	 */
	void recordFailure(String productId, Kind kind, String error);

	/**
	 * Records one completed scheduled pass, so the incremental behaviour can be inspected after the
	 * fact — "how many products did last night's price run actually write?" is otherwise a question
	 * only the logs can answer, and only until they rotate.
	 */
	void recordRun(String job, Instant startedAt, int processed, int pushed, int failed, int skipped);
}
