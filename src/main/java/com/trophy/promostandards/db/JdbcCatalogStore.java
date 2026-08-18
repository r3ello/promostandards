package com.trophy.promostandards.db;

import com.trophy.promostandards.sync.SyncProperties;
import com.trophy.promostandards.sync.model.ProductGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Postgres-backed {@link CatalogStore}. Active only when {@code sync.persistence.enabled=true}.
 *
 * <p>Every write is an upsert keyed on {@code (supplier_code, product_key)} so re-running a scan is
 * idempotent, and every id is keyed on its upper-cased form because supplier casing is inconsistent
 * between the live feed and the ids inherited from the legacy migration.
 */
@Repository
@ConditionalOnProperty(prefix = "sync.persistence", name = "enabled", havingValue = "true")
public class JdbcCatalogStore implements CatalogStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcCatalogStore.class);

	private static final String UPSERT_PRODUCT = """
			insert into supplier_product (supplier_code, product_key, product_id, title, vendor,
			                              product_type, close_out, sellable, product_data_missing,
			                              last_seen_at, updated_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (supplier_code, product_key) do update set
			    product_id           = excluded.product_id,
			    title                = excluded.title,
			    vendor               = excluded.vendor,
			    product_type         = excluded.product_type,
			    close_out            = excluded.close_out,
			    sellable             = excluded.sellable,
			    product_data_missing = excluded.product_data_missing,
			    last_seen_at         = excluded.last_seen_at,
			    updated_at           = excluded.updated_at
			""";

	private static final String SELECT_COLUMNS =
			"product_id, title, vendor, product_type, close_out, sellable, product_data_missing";

	private final JdbcClient jdbc;
	private final JdbcTemplate jdbcTemplate;
	private final String supplierCode;

	public JdbcCatalogStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate, SyncProperties props) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.supplierCode = props.supplierCode();
	}

	@Override
	@Transactional
	public int saveCatalog(List<CatalogRow> rows) {
		if (rows.isEmpty()) {
			return 0;   // never let an empty scan blank the mirror
		}
		Timestamp scannedAt = Timestamp.from(Instant.now());
		jdbcTemplate.batchUpdate(UPSERT_PRODUCT, rows, 500, (ps, row) -> {
			ps.setString(1, supplierCode);
			ps.setString(2, row.productKey());
			ps.setString(3, row.productId());
			ps.setString(4, row.title());
			ps.setString(5, row.vendor());
			ps.setString(6, row.productType());
			ps.setBoolean(7, row.closeOut());
			ps.setBoolean(8, row.sellable());
			ps.setBoolean(9, row.productDataMissing());
			ps.setTimestamp(10, scannedAt);
			ps.setTimestamp(11, scannedAt);
		});

		// Anything the scan did not touch has dropped out of the supplier's catalog. Mark it, do not
		// delete it: it may still exist in Shopify, and the row carries its link and sync state.
		int retired = jdbc.sql("""
						update supplier_product set sellable = false, updated_at = ?
						where supplier_code = ? and last_seen_at < ? and sellable
						""")
				.param(scannedAt).param(supplierCode).param(scannedAt)
				.update();
		if (retired > 0) {
			log.info("Catalog mirror: {} product(s) no longer sellable at the supplier", retired);
		}
		return rows.size();
	}

	@Override
	public List<CatalogRow> findAll() {
		return jdbc.sql("select " + SELECT_COLUMNS + """
						 from supplier_product where supplier_code = ?
						 order by sellable desc, product_id
						""")
				.param(supplierCode)
				.query(JdbcCatalogStore::mapRow)
				.list();
	}

	@Override
	public CatalogQuery.Page search(CatalogQuery.Request request) {
		List<Object> params = new ArrayList<>();
		params.add(supplierCode);
		StringBuilder where = new StringBuilder("where supplier_code = ?");

		if (request.search() != null && !request.search().isBlank()) {
			// Matched against the three fields a person would type: id, name, brand.
			where.append(" and (product_id ilike ? or title ilike ? or vendor ilike ?)");
			String like = "%" + request.search().trim() + "%";
			params.add(like);
			params.add(like);
			params.add(like);
		}
		switch (request.status()) {
			case CLOSE_OUT -> where.append(" and close_out");
			case NO_PRODUCT_DATA -> where.append(" and product_data_missing");
			case DISCONTINUED -> where.append(" and not sellable");
			case ALL -> { /* no extra predicate */ }
		}

		Long total = jdbc.sql("select count(*) from supplier_product " + where)
				.params(params).query(Long.class).single();

		List<Object> pageParams = new ArrayList<>(params);
		pageParams.add(request.size());
		pageParams.add(request.page() * request.size());
		List<CatalogRow> rows = jdbc.sql("select " + SELECT_COLUMNS + " from supplier_product " + where
						+ " order by " + orderBy(request) + " limit ? offset ?")
				.params(pageParams)
				.query(JdbcCatalogStore::mapRow)
				.list();

		return new CatalogQuery.Page(rows, total == null ? 0 : total, request.page(), request.size());
	}

	/**
	 * Built from the enum, never from user input — the sort key reaches SQL as an identifier and
	 * cannot be parameterised. product_id is appended as a tiebreaker so paging stays stable when
	 * many rows share a title or vendor.
	 */
	private static String orderBy(CatalogQuery.Request request) {
		String direction = request.ascending() ? "asc" : "desc";
		return switch (request.sort()) {
			case TITLE -> "lower(title) " + direction + " nulls last, product_id";
			case VENDOR -> "lower(vendor) " + direction + " nulls last, product_id";
			case PRODUCT_ID -> "product_id " + direction;
		};
	}

	@Override
	public Optional<Instant> lastScanAt() {
		Timestamp max = jdbc.sql("select max(last_seen_at) from supplier_product where supplier_code = ?")
				.param(supplierCode).query(Timestamp.class).optional().orElse(null);
		return Optional.ofNullable(max).map(Timestamp::toInstant);
	}

	@Override
	@Transactional
	public void saveGroups(List<ProductGroup> groups) {
		jdbc.sql("delete from product_group_member where supplier_code = ?").param(supplierCode).update();
		List<Object[]> rows = new ArrayList<>();
		for (ProductGroup group : groups) {
			String primaryKey = group.primaryId().toUpperCase(Locale.ROOT);
			for (String memberId : group.memberIds()) {
				rows.add(new Object[] {supplierCode, memberId.toUpperCase(Locale.ROOT), primaryKey, "index"});
			}
		}
		if (!rows.isEmpty()) {
			jdbcTemplate.batchUpdate("""
					insert into product_group_member (supplier_code, product_key, group_primary_key, source)
					values (?, ?, ?, ?)
					""", rows);
		}
	}

	@Override
	public List<ProductGroup> findGroups() {
		// Members come back keyed by family; the mirror stores upper-cased keys, so the catalog's own
		// casing is restored by joining back to supplier_product.
		record Member(String primaryKey, String productId) {
		}
		List<Member> members = jdbc.sql("""
						select m.group_primary_key, coalesce(p.product_id, m.product_key) as product_id
						from product_group_member m
						left join supplier_product p
						       on p.supplier_code = m.supplier_code and p.product_key = m.product_key
						where m.supplier_code = ?
						order by m.group_primary_key, product_id
						""")
				.param(supplierCode)
				.query((rs, rowNum) -> new Member(rs.getString(1), rs.getString(2)))
				.list();

		Map<String, List<String>> byFamily = new LinkedHashMap<>();
		for (Member member : members) {
			byFamily.computeIfAbsent(member.primaryKey(), k -> new ArrayList<>()).add(member.productId());
		}
		List<ProductGroup> groups = new ArrayList<>();
		byFamily.forEach((primaryKey, ids) -> groups.add(new ProductGroup(ids.get(0), List.copyOf(ids))));
		return groups;
	}

	private static CatalogRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new CatalogRow(rs.getString("product_id"), rs.getString("title"), rs.getString("vendor"),
				rs.getString("product_type"), rs.getBoolean("close_out"), rs.getBoolean("sellable"),
				rs.getBoolean("product_data_missing"));
	}
}
