package com.trophy.promostandards.db;

import com.trophy.promostandards.sync.SyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Postgres-backed {@link ProductLinkStore}. Active only when {@code sync.persistence.enabled=true}. */
@Repository
@ConditionalOnProperty(prefix = "sync.persistence", name = "enabled", havingValue = "true")
public class JdbcProductLinkStore implements ProductLinkStore {

	private static final Logger log = LoggerFactory.getLogger(JdbcProductLinkStore.class);

	/** first_seen_at is written on insert only: it is when the pair was first observed. */
	private static final String UPSERT = """
			insert into shopify_product_link (supplier_code, product_key, shopify_gid, product_id, handle,
			                                  source, canonical, first_seen_at, last_seen_at, unlinked_at)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, null)
			on conflict (supplier_code, product_key, shopify_gid) do update set
			    product_id   = excluded.product_id,
			    handle       = excluded.handle,
			    source       = excluded.source,
			    canonical    = excluded.canonical,
			    last_seen_at = excluded.last_seen_at,
			    unlinked_at  = null
			""";

	private final JdbcClient jdbc;
	private final JdbcTemplate jdbcTemplate;
	private final String supplierCode;

	public JdbcProductLinkStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate, SyncProperties props) {
		this.jdbc = jdbc;
		this.jdbcTemplate = jdbcTemplate;
		this.supplierCode = props.supplierCode();
	}

	@Override
	@Transactional
	public int recordListing(List<Link> links, Instant seenAt) {
		if (links.isEmpty()) {
			return 0;   // a store that answered nothing must not unlink the whole record
		}
		// One row per (id, product): the same pair listed twice would only rewrite itself.
		Map<String, Link> pairs = new LinkedHashMap<>();
		for (Link link : links) {
			if (link.productId() == null || link.productId().isBlank()
					|| link.shopifyGid() == null || link.shopifyGid().isBlank()) {
				continue;
			}
			pairs.putIfAbsent(key(link.productId()) + "|" + link.shopifyGid(), link);
		}
		Timestamp at = Timestamp.from(seenAt);
		jdbcTemplate.batchUpdate(UPSERT, new ArrayList<>(pairs.values()), 500, (ps, link) -> {
			ps.setString(1, supplierCode);
			ps.setString(2, key(link.productId()));
			ps.setString(3, link.shopifyGid());
			ps.setString(4, link.productId().trim());
			ps.setString(5, link.handle());
			ps.setString(6, link.source());
			ps.setBoolean(7, link.canonical());
			ps.setTimestamp(8, at);
			ps.setTimestamp(9, at);
		});
		// Linked before, not in this listing: the product no longer carries the id. Kept, not deleted —
		// keeping it is the point of the table.
		int unlinked = jdbc.sql("""
						update shopify_product_link set unlinked_at = ?
						where supplier_code = ? and unlinked_at is null and last_seen_at < ?
						""")
				.param(at).param(supplierCode).param(at)
				.update();
		if (unlinked > 0) {
			log.info("Product links: {} supplier id(s) no longer on the store product that carried them",
					unlinked);
		}
		return unlinked;
	}

	@Override
	public List<LinkRow> history(String productId) {
		return jdbc.sql("""
						select product_id, shopify_gid, handle, source, canonical,
						       first_seen_at, last_seen_at, unlinked_at
						from shopify_product_link
						where supplier_code = ? and product_key = ?
						order by unlinked_at is not null, last_seen_at desc
						""")
				.param(supplierCode).param(key(productId))
				.query(JdbcProductLinkStore::mapRow)
				.list();
	}

	private static LinkRow mapRow(ResultSet rs, int rowNum) throws SQLException {
		Timestamp unlinked = rs.getTimestamp("unlinked_at");
		return new LinkRow(rs.getString("product_id"), rs.getString("shopify_gid"), rs.getString("handle"),
				rs.getString("source"), rs.getBoolean("canonical"),
				rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(),
				unlinked == null ? null : unlinked.toInstant());
	}

	private static String key(String productId) {
		return productId.trim().toUpperCase(Locale.ROOT);
	}
}
