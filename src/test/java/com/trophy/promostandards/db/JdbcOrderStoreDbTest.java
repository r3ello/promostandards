package com.trophy.promostandards.db;

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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The order bookkeeping SQL against a real Postgres. Skipped unless {@code TEST_DB_URL} is set — see
 * {@link JdbcCatalogStoreDbTest}.
 *
 * <p>{@code order_shipment_pushed} is the only table here whose loss is visible to a customer: an
 * unrecorded fulfillment becomes a duplicate shipping notification on the next run.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class JdbcOrderStoreDbTest {

	private static final String SUPPLIER = "IT-TEST";
	private static final String PO = "PO-IT-1001";

	private static DataSource dataSource;
	private JdbcOrderStore store;
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
		store = new JdbcOrderStore(JdbcClient.create(dataSource), syncProps());
		jdbcTemplate.update("delete from order_link where supplier_code = ?", SUPPLIER);
		jdbcTemplate.update("delete from order_shipment_pushed where supplier_code = ?", SUPPLIER);
		jdbcTemplate.update("delete from job_watermark where job like ?", SUPPLIER + ":%");
	}

	private static SyncProperties syncProps() {
		return new SyncProperties(SUPPLIER, "USD", "US", "en", SyncProperties.SkuStrategy.PART_SIZE,
				new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
				new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
	}

	@Test
	void storesAndReadsBackAPoToOrderMatch() {
		store.saveOrder(PO, "gid://shopify/Order/500", "#1001");

		OrderStore.OrderLink link = store.findOrder(PO).orElseThrow();
		assertThat(link.shopifyOrderGid()).isEqualTo("gid://shopify/Order/500");
		assertThat(link.shopifyOrderName()).isEqualTo("#1001");
	}

	@Test
	void hasNoLinkForAnUnmatchedPo() {
		assertThat(store.findOrder("PO-NEVER-SEEN")).isEmpty();
	}

	/** Re-matching corrects the stored id rather than adding a second row for the same PO. */
	@Test
	void rematchingUpdatesTheSameRow() {
		store.saveOrder(PO, "gid://shopify/Order/deleted", "#1001");
		store.saveOrder(PO, "gid://shopify/Order/500", "#1001");

		assertThat(store.findOrder(PO).orElseThrow().shopifyOrderGid())
				.isEqualTo("gid://shopify/Order/500");
		Integer rows = jdbcTemplate.queryForObject(
				"select count(*) from order_link where supplier_code = ? and po_number = ?",
				Integer.class, SUPPLIER, PO);
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void recordsAShipmentAsPushedExactlyOnce() {
		assertThat(store.isShipmentPushed(PO, "SO-1|1Z999")).isFalse();

		store.recordShipmentPushed(PO, "SO-1|1Z999", "gid://shopify/Fulfillment/1");

		assertThat(store.isShipmentPushed(PO, "SO-1|1Z999")).isTrue();
	}

	/**
	 * Recording the same shipment twice must be harmless: the second call happens whenever a run is
	 * retried, and an exception there would abort the rest of the order sync.
	 */
	@Test
	void recordingTheSameShipmentTwiceIsHarmless() {
		store.recordShipmentPushed(PO, "SO-1|1Z999", "gid://shopify/Fulfillment/1");
		store.recordShipmentPushed(PO, "SO-1|1Z999", "gid://shopify/Fulfillment/2");

		Integer rows = jdbcTemplate.queryForObject(
				"select count(*) from order_shipment_pushed where supplier_code = ? and po_number = ?",
				Integer.class, SUPPLIER, PO);
		assertThat(rows).isEqualTo(1);
	}

	/** Two parcels on one PO are separate shipments; fulfilling one must not suppress the other. */
	@Test
	void tracksEachPackageOfAnOrderSeparately() {
		store.recordShipmentPushed(PO, "SO-1|1Z999", "gid://shopify/Fulfillment/1");

		assertThat(store.isShipmentPushed(PO, "SO-1|1Z999")).isTrue();
		assertThat(store.isShipmentPushed(PO, "SO-1|1Z888")).isFalse();
	}

	@Test
	void storesAndAdvancesTheJobWatermark() {
		assertThat(store.watermark("orders")).isEmpty();

		Instant first = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.MILLIS);
		store.saveWatermark("orders", first);
		assertThat(store.watermark("orders")).contains(first);

		Instant later = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		store.saveWatermark("orders", later);
		assertThat(store.watermark("orders")).contains(later);
	}
}
