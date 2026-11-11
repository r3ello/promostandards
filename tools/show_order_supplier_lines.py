"""Show whose each line of a Shopify order is, by the marks the PaceSetter rule reads.

For every line: its product's `trophy_sync.vendor` and `custom.ps_product_id`, its variant's
`trophy_sync.vendor_sku`, and what the rule makes of it — the rule of
`SupplierOrderService.toPreview` and of the order action's `ShouldRender.ts` (print app), which
must agree. Read-only. Answers "why is Send to PaceSetter offered (or not) on this order?".

Usage:
    python tools/show_order_supplier_lines.py 1173
    python tools/show_order_supplier_lines.py 7300090560606     # numeric id or order GID also work
    python tools/show_order_supplier_lines.py recent            # the 30 newest orders, one line each
    python tools/show_order_supplier_lines.py 1173 --store trophy-partner-dev.myshopify.com
                                    # the other store, when application-local.yaml keeps its
                                    # credentials commented out next to the active ones

Exit code: 0 when read, 1 when the order is not on the configured store.
Credentials: the client_credentials grant from application-local.yaml.

Requires: standard library only.
Origin: 2026-11-10, the action still showing on order #1173 after the vendor rule was deployed.
"""

from __future__ import annotations

import argparse
import re
import sys

from check_discount_metafield import LOCAL_YAML, access_token, graphql, local_credentials, report_errors

SUPPLIER = "pacesetter"

FIELDS = """
  id name
  lineItems(first: 100) {
    nodes {
      title sku quantity unfulfilledQuantity
      variant { id vendorSku: metafield(namespace: "trophy_sync", key: "vendor_sku") { value } }
      product {
        id handle status
        vendor: metafield(namespace: "trophy_sync", key: "vendor") { value }
        psId: metafield(namespace: "custom", key: "ps_product_id") { value }
      }
    }
  }
"""

FIND = "query FindOrder($q: String!) { orders(first: 1, query: $q) { nodes { %s } } }" % FIELDS
BY_ID = "query OrderById($id: ID!) { order(id: $id) { %s } }" % FIELDS
RECENT = ("query RecentOrders { orders(first: 30, sortKey: CREATED_AT, reverse: true) "
          "{ nodes { displayFulfillmentStatus %s } } }" % FIELDS)


def value(node: dict | None, key: str) -> str | None:
    field = (node or {}).get(key) or {}
    text = (field.get("value") or "").strip()
    return text or None


def verdict(vendor: str | None, ps_id: str | None, product: dict | None) -> str:
    if product is None:
        return "not PaceSetter (the product no longer exists)"
    if vendor:
        return "PACESETTER" if vendor.lower() == SUPPLIER else f"not PaceSetter ({vendor} product)"
    if ps_id:
        return "PACESETTER, never synced (no vendor, has ps_product_id) - shown, blocks the send"
    return "not PaceSetter (no vendor, no ps_product_id)"


def commented_credentials(store: str) -> tuple[str, str] | None:
    """client id and secret of a store whose `shopify:` lines are commented out in the local yaml."""
    block = LOCAL_YAML.read_text(encoding="utf-8")
    block = block[block.index("\nshopify:"):]

    def commented(name: str) -> str | None:
        match = re.search(rf'^#\s*{name}:\s*"?([^"\n]+)"?', block, re.MULTILINE)
        return match.group(1).strip() if match else None

    if commented("store-domain") != store:
        return None
    client_id, secret = commented("client-id"), commented("client-secret")
    return (client_id, secret) if client_id and secret else None


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("order", help="order number (1173), numeric id or order GID")
    parser.add_argument("--store", help="the store whose credentials are commented out in the local yaml")
    args = parser.parse_args()

    store, client_id, secret, version = local_credentials()
    if args.store and args.store != store:
        other = commented_credentials(args.store)
        if not other:
            print(f"no credentials for {args.store} in {LOCAL_YAML.name} (active or commented out)")
            return 2
        store, (client_id, secret) = args.store, other
    token = access_token(store, client_id, secret)
    if args.order == "recent":
        # The newest orders and whether the action would be offered on each: how to find one to test.
        payload = graphql(store, version, token, RECENT, {})
        report_errors("orders", payload)
        for order in ((payload.get("data") or {}).get("orders") or {}).get("nodes") or []:
            vendors = []
            for line in order["lineItems"]["nodes"]:
                product = line.get("product")
                vendors.append(verdict(value(product, "vendor"), value(product, "psId"), product).split(" (")[0]
                               if not value(product, "vendor") else value(product, "vendor"))
            offered = any(verdict(value(l.get("product"), "vendor"), value(l.get("product"), "psId"),
                                  l.get("product")).startswith("PACESETTER")
                          for l in order["lineItems"]["nodes"])
            print(f"{order['name']:>7}  {'OFFERED' if offered else '-':8} {order['displayFulfillmentStatus']:12} "
                  f"{order['id'].rsplit('/', 1)[1]}  {', '.join(sorted(set(vendors)))}")
        return 0
    ref = args.order.strip().lstrip("#")
    if ref.startswith("gid://") or len(ref) > 9:
        gid = ref if ref.startswith("gid://") else f"gid://shopify/Order/{ref}"
        payload = graphql(store, version, token, BY_ID, {"id": gid})
        report_errors("order", payload)
        order = (payload.get("data") or {}).get("order")
    else:
        payload = graphql(store, version, token, FIND, {"q": f"name:#{ref}"})
        report_errors("orders", payload)
        nodes = ((payload.get("data") or {}).get("orders") or {}).get("nodes") or []
        order = nodes[0] if nodes else None
    if not order:
        print(f"no order {args.order} on {store}")
        return 1

    print(f"{order['name']} on {store} ({order['id']})")
    shown = False
    for line in order["lineItems"]["nodes"]:
        product = line.get("product")
        vendor, ps_id = value(product, "vendor"), value(product, "psId")
        result = verdict(vendor, ps_id, product)
        shown |= result.startswith("PACESETTER")
        print(f"  {line['title']}  (sku {line.get('sku')}, {line['unfulfilledQuantity']}/{line['quantity']} open)")
        if product:
            print(f"      product {product['handle']} [{product['status']}]")
        print(f"      trophy_sync.vendor={vendor}  custom.ps_product_id={ps_id}  "
              f"vendor_sku={value(line.get('variant'), 'vendorSku')}")
        print(f"      -> {result}")
    print(f"action offered by the rule: {'YES' if shown else 'NO'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
