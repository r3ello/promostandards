package com.trophy.promostandards.db;

import com.trophy.promostandards.sync.SyncProperties;
import com.trophy.promostandards.sync.SyncProperties.Pricing;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Rounding;
import com.trophy.promostandards.sync.SyncProperties.Pricing.Strategy;
import com.trophy.promostandards.sync.model.ProductGroup;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the actual SQL against a real Postgres. Skipped unless {@code TEST_DB_URL} is set, which
 * is how the offline suite stays green on the development machine (no Docker there, so no
 * Testcontainers) while the SQL still gets verified wherever a database exists:
 *
 * <pre>
 * TEST_DB_URL=jdbc:postgresql://localhost:5432/promostandards \
 * TEST_DB_USER=promostandards TEST_DB_PASSWORD=… mvn test
 * </pre>
 *
 * <p>H2 in Postgres mode is deliberately not used instead: {@code on conflict}, {@code ilike} and
 * {@code timestamptz} differ enough that a green H2 run would say nothing about the SQL that ships.
 *
 * <p>All rows are written under a dedicated supplier code and deleted around each test, so pointing
 * this at a database that holds real data cannot disturb it.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")
class JdbcCatalogStoreDbTest {

	private static final String SUPPLIER = "IT-TEST";

	private static DataSource dataSource;
	private JdbcCatalogStore store;
	private JdbcTemplate jdbcTemplate;

	@BeforeAll
	static void migrate() {
		DriverManagerDataSource source = new DriverManagerDataSource(System.getenv("TEST_DB_URL"),
				System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
		source.setDriverClassName("org.postgresql.Driver");
		dataSource = source;
		// Also the check that V1__init.sql actually applies: a broken migration fails right here.
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@BeforeEach
	void reset() {
		jdbcTemplate = new JdbcTemplate(dataSource);
		store = new JdbcCatalogStore(JdbcClient.create(dataSource), jdbcTemplate, syncProps());
		jdbcTemplate.update("delete from supplier_product where supplier_code = ?", SUPPLIER);
		jdbcTemplate.update("delete from product_group_member where supplier_code = ?", SUPPLIER);
	}

	private static SyncProperties syncProps() {
		return new SyncProperties(SUPPLIER, "USD", "US", "en", SyncProperties.SkuStrategy.PART_SIZE,
				new Pricing(Strategy.MARKUP, new BigDecimal("40"), Rounding.NONE, false),
				new SyncProperties.Schedule(false, "-", "-", "-", false), List.of(), null);
	}

	private static CatalogRow row(String id, String title, boolean closeOut) {
		return new CatalogRow(id, title, "PaceSetter", "Awards", closeOut, true, title == null);
	}

	@Test
	void savesAndReadsBackTheCatalog() {
		store.saveCatalog(List.of(row("A1", "Crystal Award", false), row("GI840", null, true)));

		assertThat(store.findAll()).extracting(CatalogRow::productId).containsExactly("A1", "GI840");
		assertThat(store.findAll()).anySatisfy(r -> {
			assertThat(r.productId()).isEqualTo("GI840");
			assertThat(r.productDataMissing()).isTrue();
			assertThat(r.closeOut()).isTrue();
		});
		assertThat(store.lastScanAt()).isPresent();
	}

	/** Re-running a scan must merge, not duplicate: every write is keyed on (supplier, product_key). */
	@Test
	void rescanningIsIdempotentAndUpdatesInPlace() {
		store.saveCatalog(List.of(row("A1", "Old name", false)));
		store.saveCatalog(List.of(row("A1", "New name", false)));

		assertThat(store.findAll()).singleElement()
				.satisfies(r -> assertThat(r.title()).isEqualTo("New name"));
	}

	/**
	 * The supplier's ids arrive with inconsistent casing (the migration parsed some from a legacy
	 * database). Keying on the raw id would silently produce two rows for one product.
	 */
	@Test
	void treatsIdsCaseInsensitively() {
		store.saveCatalog(List.of(row("abc-1", "First", false)));
		store.saveCatalog(List.of(row("ABC-1", "Second", false)));

		assertThat(store.findAll()).singleElement().satisfies(r -> {
			assertThat(r.title()).isEqualTo("Second");
			assertThat(r.productId()).isEqualTo("ABC-1");   // latest casing wins for display
		});
	}

	/** An id that drops out of the feed is retired, never deleted — its link and state live on it. */
	@Test
	void marksProductsMissingFromAScanAsNotSellable() {
		store.saveCatalog(List.of(row("A1", "Stays", false), row("A2", "Goes away", false)));
		store.saveCatalog(List.of(row("A1", "Stays", false)));

		assertThat(store.findAll()).hasSize(2);
		assertThat(store.findAll()).anySatisfy(r -> {
			assertThat(r.productId()).isEqualTo("A2");
			assertThat(r.sellable()).isFalse();
		});
	}

	/** An empty scan is a failed scan, not an empty catalog: it must not blank the mirror. */
	@Test
	void ignoresAnEmptyScan() {
		store.saveCatalog(List.of(row("A1", "Crystal Award", false)));

		assertThat(store.saveCatalog(List.of())).isZero();
		assertThat(store.findAll()).hasSize(1);
	}

	@Test
	void searchesFiltersSortsAndPages() {
		store.saveCatalog(List.of(
				row("A1", "Crystal Award", false),
				row("A2", "Crystal Plaque", true),
				row("B1", "Wooden Trophy", false)));

		CatalogQuery.Page crystal = store.search(new CatalogQuery.Request(
				"crystal", CatalogQuery.Status.ALL, CatalogQuery.Sort.TITLE, true, 0, 50));
		assertThat(crystal.total()).isEqualTo(2);
		assertThat(crystal.rows()).extracting(CatalogRow::productId).containsExactly("A1", "A2");

		CatalogQuery.Page closeOut = store.search(new CatalogQuery.Request(
				null, CatalogQuery.Status.CLOSE_OUT, CatalogQuery.Sort.PRODUCT_ID, true, 0, 50));
		assertThat(closeOut.rows()).extracting(CatalogRow::productId).containsExactly("A2");

		CatalogQuery.Page firstPage = store.search(new CatalogQuery.Request(
				null, CatalogQuery.Status.ALL, CatalogQuery.Sort.PRODUCT_ID, true, 0, 2));
		assertThat(firstPage.rows()).hasSize(2);
		assertThat(firstPage.total()).isEqualTo(3);
		assertThat(firstPage.totalPages()).isEqualTo(2);

		CatalogQuery.Page descending = store.search(new CatalogQuery.Request(
				null, CatalogQuery.Status.ALL, CatalogQuery.Sort.PRODUCT_ID, false, 0, 1));
		assertThat(descending.rows()).extracting(CatalogRow::productId).containsExactly("B1");
	}

	/** Search is case-insensitive and matches ids as well as names — people type both. */
	@Test
	void searchMatchesIdsAndIgnoresCase() {
		store.saveCatalog(List.of(row("GI840", "Crystal Rectangle", false)));

		assertThat(store.search(new CatalogQuery.Request("gi840", null, null, true, 0, 50)).rows())
				.hasSize(1);
		assertThat(store.search(new CatalogQuery.Request("CRYSTAL", null, null, true, 0, 50)).rows())
				.hasSize(1);
	}

	@Test
	void storesAndRestoresVariantFamilies() {
		store.saveCatalog(List.of(
				row("TROPHY-SM", "Trophy Small", false),
				row("TROPHY-MD", "Trophy Medium", false)));
		store.saveGroups(List.of(new ProductGroup("TROPHY-MD", List.of("TROPHY-MD", "TROPHY-SM"))));

		assertThat(store.findGroups()).singleElement().satisfies(group ->
				assertThat(group.memberIds()).containsExactly("TROPHY-MD", "TROPHY-SM"));
	}

	/** Families are recomputed as a set, so a rebuild replaces rather than accumulates. */
	@Test
	void replacesFamiliesOnRebuild() {
		store.saveGroups(List.of(new ProductGroup("A1", List.of("A1", "A2"))));
		store.saveGroups(List.of(new ProductGroup("B1", List.of("B1", "B2"))));

		assertThat(store.findGroups()).singleElement().satisfies(group ->
				assertThat(group.memberIds()).containsExactly("B1", "B2"));
	}
}
