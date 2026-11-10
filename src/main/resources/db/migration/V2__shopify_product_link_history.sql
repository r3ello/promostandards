-- shopify_product_link, rebuilt as a record of which store product carries which supplier id.
--
-- V1 created it keyed by supplier id alone, but nothing ever wrote it (it is empty on every
-- database), so it is dropped and recreated rather than altered.
--
-- What it is and is not:
--  * NOT the source of truth. The store's metafields (custom.ps_product_id / ps_product_ids) are, and
--    the sync never reads this table to decide anything. It is written from the store index each time
--    that index is rebuilt, so it answers "which product carried this id, and since when" after the
--    fact — including after someone edits or deletes the metafield by hand.
--  * Keyed by the pair (supplier id, Shopify product GID), not by the id: one id can sit on two
--    products (a duplicate, or a grouping half-applied), and an id that moves to another product
--    keeps its old row with unlinked_at set. That is the history.
--  * The GID is the identity. handle is only the last one observed, for a human reading the row:
--    a handle can be renamed in the admin, a GID never changes.
drop table if exists shopify_product_link;

create table shopify_product_link (
    supplier_code text        not null,
    product_key   text        not null,
    shopify_gid   text        not null,
    product_id    text        not null,
    handle        text,
    source        text,
    canonical     boolean     not null default false,
    first_seen_at timestamptz not null,
    last_seen_at  timestamptz not null,
    unlinked_at   timestamptz,
    primary key (supplier_code, product_key, shopify_gid)
);

comment on table shopify_product_link is $$Which store product carried which supplier id, as the store index saw it. A record, never read by the sync: the metafields custom.ps_product_id(s) are the truth.$$;
comment on column shopify_product_link.product_key is $$upper(product_id), the join key of every table.$$;
comment on column shopify_product_link.shopify_gid is $$gid://shopify/Product/<n>: the identity of the store product. Never changes, unlike the handle.$$;
comment on column shopify_product_link.handle is $$Last handle observed. For reading only; a handle can be renamed in the admin.$$;
comment on column shopify_product_link.source is $$trophy_sync.source (or the migration's custom.ps_source): app = created by this service, migration = the one-shot trophypartner migration.$$;
comment on column shopify_product_link.canonical is $$True when the id is the product's custom.ps_product_id, not just one of its ps_product_ids.$$;
comment on column shopify_product_link.unlinked_at is $$Null while the product still carries the id. Set when a full store listing no longer shows the pair (id moved, metafield removed, product deleted or untagged); cleared if it comes back.$$;

create index shopify_product_link_gid_idx on shopify_product_link (shopify_gid);
create index shopify_product_link_current_idx on shopify_product_link (supplier_code, product_key)
    where unlinked_at is null;
