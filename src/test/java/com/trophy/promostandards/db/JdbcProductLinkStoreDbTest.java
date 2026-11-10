package com.trophy.promostandards.db;

import com.trophy.promostandards.db.ProductLinkStore.Link;
import com.trophy.promostandards.db.ProductLinkStore.LinkRow;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The product-link record against a real Postgres. Skipped unless {@code TEST_DB_URL} is set — see
 * {@link JdbcCatalogStoreDbTest} for the command that runs it.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class JdbcProductLinkStoreDbTest {

	private static final String SUPPLIER = "IT-TEST";
	private static final String A = "gid://shopify/Product/1";
	private static final String B = "gid://shopify/Product/2";

	private static DataSource dataSource;
	private JdbcProductLinkStore store;
	private final Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

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
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		store = new JdbcProductLinkStore(JdbcClient.create(dataSource), jdbcTemplate, syncProps());
		jdbcTemplate.update("delete from shopify_product_link where supplier_code = ?", SUPPLIER);
	}

	private static SyncProperties syncProps() {
		return new SyncProperties(SUPPLIER, "USD", "US", "en", SyncProperties.SkuStrategy.PART_SIZE,
				new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
				new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
	}

	@Test
	void recordsTheProductAnIdIsOnKeyedCaseInsensitively() {
		store.recordListing(List.of(new Link("cm373bs", A, "p-1-pen", "migration", true)), t0);

		List<LinkRow> rows = store.history("CM373BS");
		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.shopifyGid()).isEqualTo(A);
			assertThat(row.canonical()).isTrue();
			assertThat(row.unlinkedAt()).isNull();
			assertThat(row.firstSeenAt()).isEqualTo(t0);
		});
	}

	/** A renamed handle is the same product: same row, the new handle, first_seen_at kept. */
	@Test
	void aRenamedHandleUpdatesTheRowItDoesNotStartANewOne() {
		store.recordListing(List.of(new Link("CM373BS", A, "p-1-pen", "migration", true)), t0);
		store.recordListing(List.of(new Link("CM373BS", A, "p-1-pen-renamed", "migration", true)),
				t0.plusSeconds(60));

		assertThat(store.history("CM373BS")).singleElement().satisfies(row -> {
			assertThat(row.handle()).isEqualTo("p-1-pen-renamed");
			assertThat(row.firstSeenAt()).isEqualTo(t0);
			assertThat(row.lastSeenAt()).isEqualTo(t0.plusSeconds(60));
		});
	}

	/** An id that moved keeps where it was, unlinked; it comes back to life if it returns. */
	@Test
	void anIdThatMovesKeepsItsOldProductAsHistory() {
		store.recordListing(List.of(new Link("CM373BS", A, "p-1", "migration", true)), t0);
		int unlinked = store.recordListing(List.of(new Link("CM373BS", B, "p-2", "migration", false)),
				t0.plusSeconds(60));

		assertThat(unlinked).isEqualTo(1);
		List<LinkRow> rows = store.history("CM373BS");
		assertThat(rows).extracting(LinkRow::shopifyGid).containsExactly(B, A);
		assertThat(rows.get(0).unlinkedAt()).isNull();
		assertThat(rows.get(1).unlinkedAt()).isEqualTo(t0.plusSeconds(60));

		store.recordListing(List.of(new Link("CM373BS", A, "p-1", "migration", true)), t0.plusSeconds(120));
		assertThat(store.history("CM373BS")).filteredOn(r -> r.unlinkedAt() == null)
				.extracting(LinkRow::shopifyGid).containsExactly(A);
	}

	@Test
	void anEmptyListingUnlinksNothing() {
		store.recordListing(List.of(new Link("CM373BS", A, "p-1", "migration", true)), t0);

		assertThat(store.recordListing(List.of(), t0.plusSeconds(60))).isZero();
		assertThat(store.history("CM373BS")).singleElement()
				.satisfies(row -> assertThat(row.unlinkedAt()).isNull());
	}
}
