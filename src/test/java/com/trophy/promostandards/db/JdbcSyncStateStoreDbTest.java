package com.trophy.promostandards.db;

import com.trophy.promostandards.db.SyncStateStore.Kind;
import com.trophy.promostandards.db.SyncStateStore.State;
import com.trophy.promostandards.sync.SyncProperties;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync bookkeeping SQL against a real Postgres. Skipped unless {@code TEST_DB_URL} is set — see
 * {@link JdbcCatalogStoreDbTest} for why, and for the command that runs it.
 *
 * <p>This is the table the database genuinely owns: get its SQL wrong and either every scheduled run
 * re-pushes the whole catalog, or a failed push is recorded as done and that product never syncs
 * again.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class JdbcSyncStateStoreDbTest {

	private static final String SUPPLIER = "IT-TEST";
	private static final String PRODUCT = "SAMPLE-001";

	private static DataSource dataSource;
	private JdbcSyncStateStore store;
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrate() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("TEST_DB_URL"),
				System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
		source.setDriverClassName("org.postgresql.Driver");
		dataSource = source;
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@BeforeEach
	void reset() {
		jdbcTemplate = new JdbcTemplate(dataSource);
		store = new JdbcSyncStateStore(JdbcClient.create(dataSource), syncProps());
		jdbcTemplate.update("delete from sync_state where supplier_code = ?", SUPPLIER);
		jdbcTemplate.update("delete from sync_run where job like 'it-test%'");
	}

	private static SyncProperties syncProps() {
		return new SyncProperties(SUPPLIER, "USD", "US", "en", SyncProperties.SkuStrategy.PART_SIZE,
				new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
				new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
	}

	@Test
	void hasNoStateForAProductThatWasNeverSynced() {
		assertThat(store.find(PRODUCT, Kind.INVENTORY)).isEmpty();
	}

	@Test
	void recordsAndReadsBackASuccessfulPush() {
		store.recordSuccess(PRODUCT, Kind.INVENTORY, "hash-1");

		State state = store.find(PRODUCT, Kind.INVENTORY).orElseThrow();
		assertThat(state.payloadHash()).isEqualTo("hash-1");
		assertThat(state.consecutiveFailures()).isZero();
		assertThat(state.nextAttemptAfter()).isNull();
	}

	/** Inventory and prices are tracked apart, so one cannot mark the other as done. */
	@Test
	void tracksEachKindSeparately() {
		store.recordSuccess(PRODUCT, Kind.INVENTORY, "inv-hash");

		assertThat(store.find(PRODUCT, Kind.PRICE)).isEmpty();
		assertThat(store.find(PRODUCT, Kind.INVENTORY).orElseThrow().payloadHash()).isEqualTo("inv-hash");
	}

	/** The same product syncs over and over: the row must be updated, never duplicated. */
	@Test
	void repeatedSuccessesUpdateTheSameRow() {
		store.recordSuccess(PRODUCT, Kind.INVENTORY, "hash-1");
		store.recordSuccess(PRODUCT, Kind.INVENTORY, "hash-2");

		assertThat(store.find(PRODUCT, Kind.INVENTORY).orElseThrow().payloadHash()).isEqualTo("hash-2");
		Integer rows = jdbcTemplate.queryForObject(
				"select count(*) from sync_state where supplier_code = ? and product_key = ?",
				Integer.class, SUPPLIER, PRODUCT);
		assertThat(rows).isEqualTo(1);
	}

	/** A failure must leave the last good hash alone: the product stays due until a push succeeds. */
	@Test
	void aFailureKeepsTheProductDueAndSchedulesARetry() {
		store.recordSuccess(PRODUCT, Kind.INVENTORY, "hash-1");
		store.recordFailure(PRODUCT, Kind.INVENTORY, "location not found");

		State state = store.find(PRODUCT, Kind.INVENTORY).orElseThrow();
		assertThat(state.payloadHash()).isEqualTo("hash-1");     // untouched
		assertThat(state.consecutiveFailures()).isEqualTo(1);
		assertThat(state.nextAttemptAfter()).isAfter(Instant.now());
		assertThat(state.isBackingOff(Instant.now())).isTrue();
	}

	@Test
	void repeatedFailuresBackOffFurtherEachTime() {
		store.recordFailure(PRODUCT, Kind.PRICE, "boom");
		Instant firstRetry = store.find(PRODUCT, Kind.PRICE).orElseThrow().nextAttemptAfter();
		store.recordFailure(PRODUCT, Kind.PRICE, "boom");
		State second = store.find(PRODUCT, Kind.PRICE).orElseThrow();

		assertThat(second.consecutiveFailures()).isEqualTo(2);
		assertThat(second.nextAttemptAfter()).isAfter(firstRetry);
	}

	/** A push that finally works clears the backoff, or the product would stay throttled forever. */
	@Test
	void aSuccessAfterFailuresClearsTheBackoff() {
		store.recordFailure(PRODUCT, Kind.INVENTORY, "boom");
		store.recordSuccess(PRODUCT, Kind.INVENTORY, "hash-1");

		State state = store.find(PRODUCT, Kind.INVENTORY).orElseThrow();
		assertThat(state.consecutiveFailures()).isZero();
		assertThat(state.nextAttemptAfter()).isNull();
	}

	/** Supplier ids arrive with inconsistent casing; state is keyed on the upper-cased form. */
	@Test
	void treatsProductIdsCaseInsensitively() {
		store.recordSuccess("sample-001", Kind.INVENTORY, "hash-1");

		assertThat(store.find("SAMPLE-001", Kind.INVENTORY).orElseThrow().payloadHash())
				.isEqualTo("hash-1");
	}

	/** Shopify and supplier errors can be long; the column is for diagnosis, not archiving. */
	@Test
	void truncatesAnOverlongErrorInsteadOfFailingTheWrite() {
		store.recordFailure(PRODUCT, Kind.INVENTORY, "x".repeat(5000));

		String stored = jdbcTemplate.queryForObject(
				"select last_error from sync_state where supplier_code = ? and product_key = ? and kind = 'inventory'",
				String.class, SUPPLIER, PRODUCT);
		assertThat(stored).hasSize(500);
	}

	@Test
	void recordsARunForLaterInspection() {
		store.recordRun("it-test-inventory", Instant.now().minus(Duration.ofMinutes(2)), 100, 3, 1, 96);

		Integer pushed = jdbcTemplate.queryForObject(
				"select succeeded from sync_run where job = ? order by started_at desc limit 1",
				Integer.class, "it-test-inventory");
		assertThat(pushed).isEqualTo(3);
	}

	@Test
	void backoffGrowsAndIsCapped() {
		assertThat(JdbcSyncStateStore.backoff(1)).isEqualTo(JdbcSyncStateStore.BASE_BACKOFF);
		assertThat(JdbcSyncStateStore.backoff(2)).isEqualTo(JdbcSyncStateStore.BASE_BACKOFF.multipliedBy(2));
		assertThat(JdbcSyncStateStore.backoff(50)).isEqualTo(JdbcSyncStateStore.MAX_BACKOFF);
	}
}
