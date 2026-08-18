package com.trophy.promostandards.db;

import com.trophy.promostandards.sync.SyncProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Postgres-backed {@link OrderStore}. Active only when {@code sync.persistence.enabled=true}. */
@Repository
@ConditionalOnProperty(prefix = "sync.persistence", name = "enabled", havingValue = "true")
public class JdbcOrderStore implements OrderStore {

	private final JdbcClient jdbc;
	private final String supplierCode;

	public JdbcOrderStore(JdbcClient jdbc, SyncProperties props) {
		this.jdbc = jdbc;
		this.supplierCode = props.supplierCode();
	}

	@Override
	public Optional<OrderLink> findOrder(String poNumber) {
		return jdbc.sql("""
						select po_number, shopify_order_gid, shopify_order_name
						from order_link where supplier_code = ? and po_number = ?
						""")
				.param(supplierCode).param(poNumber)
				.query((rs, rowNum) -> new OrderLink(rs.getString("po_number"),
						rs.getString("shopify_order_gid"), rs.getString("shopify_order_name")))
				.optional();
	}

	@Override
	public void saveOrder(String poNumber, String shopifyOrderGid, String shopifyOrderName) {
		jdbc.sql("""
						insert into order_link (supplier_code, po_number, shopify_order_gid,
						                        shopify_order_name, matched_at)
						values (?, ?, ?, ?, ?)
						on conflict (supplier_code, po_number) do update set
						    shopify_order_gid  = excluded.shopify_order_gid,
						    shopify_order_name = excluded.shopify_order_name,
						    matched_at         = excluded.matched_at
						""")
				.param(supplierCode).param(poNumber).param(shopifyOrderGid).param(shopifyOrderName)
				.param(Timestamp.from(Instant.now()))
				.update();
	}

	@Override
	public boolean isShipmentPushed(String poNumber, String shipmentKey) {
		Long count = jdbc.sql("""
						select count(*) from order_shipment_pushed
						where supplier_code = ? and po_number = ? and shipment_key = ?
						""")
				.param(supplierCode).param(poNumber).param(shipmentKey)
				.query(Long.class).single();
		return count != null && count > 0;
	}

	@Override
	public void recordShipmentPushed(String poNumber, String shipmentKey, String fulfillmentGid) {
		// do nothing on conflict: the shipment is already recorded, which is the whole point.
		jdbc.sql("""
						insert into order_shipment_pushed (supplier_code, po_number, shipment_key,
						                                   fulfillment_gid)
						values (?, ?, ?, ?)
						on conflict (supplier_code, po_number, shipment_key) do nothing
						""")
				.param(supplierCode).param(poNumber).param(shipmentKey).param(fulfillmentGid)
				.update();
	}

	@Override
	public Optional<Instant> watermark(String job) {
		return jdbc.sql("select cursor_at from job_watermark where job = ?")
				.param(jobKey(job))
				.query((rs, rowNum) -> rs.getTimestamp("cursor_at").toInstant())
				.optional();
	}

	@Override
	public void saveWatermark(String job, Instant cursorAt) {
		jdbc.sql("""
						insert into job_watermark (job, cursor_at, updated_at)
						values (?, ?, ?)
						on conflict (job) do update set
						    cursor_at  = excluded.cursor_at,
						    updated_at = excluded.updated_at
						""")
				.param(jobKey(job)).param(Timestamp.from(cursorAt)).param(Timestamp.from(Instant.now()))
				.update();
	}

	/** Watermarks are per supplier, so a second supplier does not inherit the first one's cursor. */
	private String jobKey(String job) {
		return supplierCode + ":" + job;
	}
}
