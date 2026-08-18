-- Catalog mirror + sync bookkeeping.
--
-- Design notes that matter for every table here:
--
--  * product_key is upper(product_id) and is THE join key. Supplier ids arrive with inconsistent
--    casing (ids parsed from the legacy migration database do not match the live feed's casing), so
--    keying on the raw id would create two rows for "sample-001" and "SAMPLE-001". product_id keeps
--    the supplier's own casing purely for display.
--  * supplier_code is on every table even though the app currently models one supplier globally.
--    It is free now and avoids a painful migration when a second supplier appears.
--  * text everywhere, never varchar(n): supplier titles carry inch marks and arbitrary punctuation
--    (6" x 9.5" x 2" Crystal rectangle) and a length cap buys nothing.
--  * timestamptz everywhere: supplier responses carry local offsets, and comparing those against a
--    naive timestamp silently shifts order-sync windows by hours.

-- ---------------------------------------------------------------------------------------------
-- Catalog mirror: refreshed from the supplier, never authoritative. Losing it costs one rescan.
-- ---------------------------------------------------------------------------------------------
create table supplier_product (
    supplier_code        text        not null,
    product_key          text        not null,
    product_id           text        not null,
    title                text,
    vendor               text,
    product_type         text,
    close_out            boolean     not null default false,
    sellable             boolean     not null default true,
    product_data_missing boolean     not null default false,
    first_seen_at        timestamptz not null default now(),
    last_seen_at         timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    primary key (supplier_code, product_key)
);

comment on column supplier_product.sellable is $$False once the id drops out of getProductSellable. The row is kept, never deleted: it may still exist in Shopify, and deleting it would lose its link and sync state.$$;
comment on column supplier_product.product_data_missing is $$True when getProductSellable lists the id but getProduct has no record for it (e.g. GI840).$$;

create index supplier_product_title_idx on supplier_product (supplier_code, lower(title));
create index supplier_product_sellable_idx on supplier_product (supplier_code, sellable, close_out);

-- Grouped variants: one row per member; a family's primary is a member of itself.
create table product_group_member (
    supplier_code     text not null,
    product_key       text not null,
    group_primary_key text not null,
    source            text not null,
    primary key (supplier_code, product_key)
);

create index product_group_member_primary_idx
    on product_group_member (supplier_code, group_primary_key);

-- ---------------------------------------------------------------------------------------------
-- Shopify link. A migrated store product can cover several supplier ids (N:1), so the link is
-- keyed by supplier product and points at the store product, not the other way round.
-- ---------------------------------------------------------------------------------------------
create table shopify_product_link (
    supplier_code text        not null,
    product_key   text        not null,
    shopify_gid   text        not null,
    handle        text        not null,
    source        text        not null,
    canonical     boolean     not null default false,
    linked_at     timestamptz not null default now(),
    primary key (supplier_code, product_key)
);

comment on column shopify_product_link.source is $$app = created by this service under its deterministic handle; migration = created by the one-shot trophypartner migration and adopted via ps_product_ids.$$;

create index shopify_product_link_gid_idx on shopify_product_link (shopify_gid);

-- ---------------------------------------------------------------------------------------------
-- Sync bookkeeping. THIS is what the database owns: it cannot be recomputed from either side, and
-- without it every scheduled run re-pushes the whole catalog.
-- ---------------------------------------------------------------------------------------------
create table sync_state (
    supplier_code        text        not null,
    product_key          text        not null,
    kind                 text        not null,
    payload_hash         text,
    last_success_at      timestamptz,
    last_attempt_at      timestamptz,
    consecutive_failures int         not null default 0,
    next_attempt_after   timestamptz,
    last_error           text,
    primary key (supplier_code, product_key, kind),
    constraint sync_state_kind_check check (kind in ('inventory', 'price', 'import'))
);

comment on column sync_state.payload_hash is $$SHA-256 of the values actually pushed (SKU -> final price / quantity), not of the supplier input, so a change to the pricing policy or the inventory location also marks work as due. Written only after the mutation succeeds without userErrors.$$;

create index sync_state_due_idx on sync_state (supplier_code, kind, next_attempt_after);

-- ---------------------------------------------------------------------------------------------
-- Orders. order_shipment_pushed is the only table whose loss has a real-world cost: without it a
-- re-processed window creates duplicate fulfillments.
-- ---------------------------------------------------------------------------------------------
create table order_link (
    supplier_code      text not null,
    po_number          text not null,
    shopify_order_gid  text,
    shopify_order_name text,
    matched_at         timestamptz,
    primary key (supplier_code, po_number)
);

create table order_shipment_pushed (
    supplier_code   text        not null,
    po_number       text        not null,
    shipment_key    text        not null,
    fulfillment_gid text,
    pushed_at       timestamptz not null default now(),
    primary key (supplier_code, po_number, shipment_key)
);

comment on table order_shipment_pushed is $$Dedupe key per shipment (tracking number + package id). A durable watermark alone is not enough: the supplier can resend a shipment inside an already-processed window.$$;

-- Durable replacement for the scheduler's in-memory order cursor, which rewinds 24h on restart.
create table job_watermark (
    job        text        primary key,
    cursor_at  timestamptz not null,
    updated_at timestamptz not null default now()
);

create table sync_run (
    id          bigserial   primary key,
    job         text        not null,
    started_at  timestamptz not null,
    finished_at timestamptz,
    processed   int,
    succeeded   int,
    failed      int,
    skipped     int,
    error       text
);

create index sync_run_job_idx on sync_run (job, started_at desc);
