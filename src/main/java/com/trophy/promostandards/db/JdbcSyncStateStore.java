package com.trophy.promostandards.db;

import com.trophy.promostandards.sync.SyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Postgres-backed {@link SyncStateStore}. Active only when {@code sync.persistence.enabled=true}.
 *
 * <p>Failures back off exponentially — 5 minutes, then 10, 20, … capped at
 * {@link #MAX_BACKOFF} — so a product that always fails (deleted in Shopify, rejected by a
 * validation rule) is retried occasionally instead of at the front of every single run.
 */
@Repository
@ConditionalOnProperty(prefix = "sync.persistence", name = "enabled", havingValue = "true")
public class JdbcSyncStateStore implements SyncStateStore {

	static final Duration BASE_BACKOFF = Duration.ofMinutes(5);
	static final Duration MAX_BACKOFF = Duration.ofHours(12);

	private final JdbcClient jdbc;
	private final String supplierCode;

	public JdbcSyncStateStore(JdbcClient jdbc, SyncProperties props) {
		this.jdbc = jdbc;
		this.supplierCode = props.supplierCode();
	}

	@Override
	public Optional<State> find(String productId, Kind kind) {
		return jdbc.sql("""
						select payload_hash, consecutive_failures, next_attempt_after
						from sync_state
						where supplier_code = ? and product_key = ? and kind = ?
						""")
				.param(supplierCode).param(key(productId)).param(kind.value())
				.query((rs, rowNum) -> new State(rs.getString("payload_hash"),
						rs.getInt("consecutive_failures"),
						Optional.ofNullable(rs.getTimestamp("next_attempt_after"))
								.map(Timestamp::toInstant).orElse(null)))
				.optional();
	}

	@Override
	public void recordSuccess(String productId, Kind kind, String payloadHash) {
		jdbc.sql("""
						insert into sync_state (supplier_code, product_key, kind, payload_hash,
						                        last_success_at, last_attempt_at,
						                        consecutive_failures, next_attempt_after, last_error)
						values (?, ?, ?, ?, ?, ?, 0, null, null)
						on conflict (supplier_code, product_key, kind) do update set
						    payload_hash         = excluded.payload_hash,
						    last_success_at      = excluded.last_success_at,
						    last_attempt_at      = excluded.last_attempt_at,
						    consecutive_failures = 0,
						    next_attempt_after   = null,
						    last_error           = null
						""")
				.param(supplierCode).param(key(productId)).param(kind.value()).param(payloadHash)
				.param(Timestamp.from(Instant.now())).param(Timestamp.from(Instant.now()))
				.update();
	}

	@Override
	public void recordFailure(String productId, Kind kind, String error) {
		int failures = find(productId, kind).map(State::consecutiveFailures).orElse(0) + 1;
		Instant retryAfter = Instant.now().plus(backoff(failures));
		// payload_hash is deliberately left as it was: the product stays due until a push succeeds.
		jdbc.sql("""
						insert into sync_state (supplier_code, product_key, kind, last_attempt_at,
						                        consecutive_failures, next_attempt_after, last_error)
						values (?, ?, ?, ?, ?, ?, ?)
						on conflict (supplier_code, product_key, kind) do update set
						    last_attempt_at      = excluded.last_attempt_at,
						    consecutive_failures = excluded.consecutive_failures,
						    next_attempt_after   = excluded.next_attempt_after,
						    last_error           = excluded.last_error
						""")
				.param(supplierCode).param(key(productId)).param(kind.value())
				.param(Timestamp.from(Instant.now())).param(failures).param(Timestamp.from(retryAfter))
				.param(truncate(error))
				.update();
	}

	@Override
	public void recordRun(String job, Instant startedAt, int processed, int pushed, int failed, int skipped) {
		jdbc.sql("""
						insert into sync_run (job, started_at, finished_at, processed, succeeded, failed, skipped)
						values (?, ?, ?, ?, ?, ?, ?)
						""")
				.param(job).param(Timestamp.from(startedAt)).param(Timestamp.from(Instant.now()))
				.param(processed).param(pushed).param(failed).param(skipped)
				.update();
	}

	/** 5 min, 10, 20, 40 … capped, so a permanently broken product stops crowding out the rest. */
	static Duration backoff(int consecutiveFailures) {
		if (consecutiveFailures <= 0) {
			return Duration.ZERO;
		}
		int shift = Math.min(consecutiveFailures - 1, 20);
		Duration backoff = BASE_BACKOFF.multipliedBy(1L << shift);
		return backoff.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff;
	}

	private String key(String productId) {
		return productId.toUpperCase(Locale.ROOT);
	}

	/** Supplier and Shopify errors can be long; the column is for diagnosis, not for archiving. */
	private static String truncate(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= 500 ? error : error.substring(0, 500);
	}
}
