"""Which PromoStandards products lack `trophy_sync.vendor`, and so block their PaceSetter orders?

`SupplierOrderService` takes an order line as PaceSetter's only when its product says
`trophy_sync.vendor = PaceSetter`; a product carrying `custom.ps_product_id` without it **blocks** the
order. The sync writes `vendor` together with `source` and `last_sync_at` in one `metafieldsSet`, and
from 2026-10-13 the live store defined `last_sync_at` as text while the app wrote it as `date_time`:
Shopify refused the whole call, so no product first synced since then got its vendor (found
2026-10-27 on C1407LR/LB). Fixed in `ShopifySyncService.stampSyncMetafields`; this finds and repairs
what it left behind.

Every tagged product carrying a supplier id is sorted into:
  OK           has trophy_sync.vendor
  MISSING      synced by this app (a variant carries trophy_sync.vendor_sku) but no vendor - the bug
  NEVER_SYNCED no vendor and no synced variant: blocks by design until its first sync

Usage:
    python tools/check_sync_vendor.py                       # report (reads application-local.yaml)
    python tools/check_sync_vendor.py --csv out.csv         # plus every MISSING / NEVER_SYNCED row
    python tools/check_sync_vendor.py --apply               # write vendor=PaceSetter on MISSING (not archived)

Requires: standard library only.
Origin: 2026-10-27, splitting C1407LG showed the restored products had no vendor.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import urllib.error
import urllib.request
from pathlib import Path

LOCAL_YAML = Path(__file__).resolve().parents[1] / "src/main/resources/application-local.yaml"

# Inlined search term: Shopify answers a tag search passed as a variable with an empty page
# (see check_discount_metafield.py).
PRODUCTS = """
query VendorAudit($cursor: String) {
  products(first: 100, after: $cursor, query: "tag:promostandards") {
    pageInfo { hasNextPage endCursor }
    nodes {
      id
      handle
      status
      psId: metafield(namespace: "custom", key: "ps_product_id") { value }
      psIds: metafield(namespace: "custom", key: "ps_product_ids") { value }
      vendor: metafield(namespace: "trophy_sync", key: "vendor") { value }
      lastSync: metafield(namespace: "trophy_sync", key: "last_sync_at") { value }
      variants(first: 10) { nodes { vendorSku: metafield(namespace: "trophy_sync", key: "vendor_sku") { value } } }
    }
  }
}
"""

METAFIELDS_SET = """
mutation MetafieldsSet($metafields: [MetafieldsSetInput!]!) {
  metafieldsSet(metafields: $metafields) {
    metafields { id }
    userErrors { field message }
  }
}
"""


def local_credentials() -> tuple[str, str, str, str]:
    """(store, client_id, client_secret, api_version) from application-local.yaml."""
    if not LOCAL_YAML.exists():
        sys.exit(f"no {LOCAL_YAML}; pass --token and --store instead")
    block = LOCAL_YAML.read_text(encoding="utf-8")
    block = block[block.index("\nshopify:"):]

    def value(name: str, default: str = "") -> str:
        match = re.search(rf"^\s+{name}:\s*\"?([^\"\n]+)\"?", block, re.MULTILINE)
        return match.group(1).strip() if match else default

    return value("store-domain"), value("client-id"), value("client-secret"), value("api-version", "2026-04")


def access_token(store: str, client_id: str, client_secret: str) -> str:
    body = json.dumps({"client_id": client_id, "client_secret": client_secret,
                       "grant_type": "client_credentials"}).encode()
    request = urllib.request.Request(f"https://{store}/admin/oauth/access_token", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def graphql(store: str, version: str, token: str, query: str, variables: dict) -> dict:
    request = urllib.request.Request(
        f"https://{store}/admin/api/{version}/graphql.json",
        data=json.dumps({"query": query, "variables": variables}).encode(),
        headers={"Content-Type": "application/json", "X-Shopify-Access-Token": token})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code}: {e.read().decode(errors='replace')[:400]}")
    if payload.get("errors"):
        sys.exit(f"GraphQL errors: {payload['errors']}")
    return payload["data"]


def value(node: dict, field: str) -> str | None:
    holder = node.get(field)
    return holder.get("value") if holder else None


def classify(node: dict) -> str | None:
    if not (value(node, "psId") or value(node, "psIds")):
        return None
    if value(node, "vendor"):
        return "OK"
    synced = any(value(v, "vendorSku") for v in node["variants"]["nodes"])
    return "MISSING" if synced else "NEVER_SYNCED"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--store")
    parser.add_argument("--token", default=os.environ.get("SHOPIFY_TOKEN"))
    parser.add_argument("--vendor", default="PaceSetter", help="value written by --apply")
    parser.add_argument("--csv", help="write the MISSING and NEVER_SYNCED rows here")
    parser.add_argument("--apply", action="store_true", help="write the vendor on MISSING, non-archived products")
    args = parser.parse_args()

    store, client_id, client_secret, version = local_credentials()
    store = args.store or store
    token = args.token or access_token(store, client_id, client_secret)

    rows, cursor = [], None
    while True:
        page = graphql(store, version, token, PRODUCTS, {"cursor": cursor})["products"]
        for node in page["nodes"]:
            verdict = classify(node)
            if verdict:
                rows.append({"verdict": verdict, "status": node["status"], "handle": node["handle"],
                             "id": node["id"], "ps_product_id": value(node, "psId") or "",
                             "ps_product_ids": value(node, "psIds") or "",
                             "last_sync_at": value(node, "lastSync") or ""})
        if not page["pageInfo"]["hasNextPage"]:
            break
        cursor = page["pageInfo"]["endCursor"]

    print(f"{store}: {len(rows)} products carrying a supplier id")
    for verdict in ("OK", "MISSING", "NEVER_SYNCED"):
        mine = [r for r in rows if r["verdict"] == verdict]
        by_status = {}
        for r in mine:
            by_status[r["status"]] = by_status.get(r["status"], 0) + 1
        print(f"  {verdict:13} {len(mine):5}  {by_status}")
    missing = [r for r in rows if r["verdict"] == "MISSING" and r["status"] != "ARCHIVED"]
    for r in missing[:40]:
        print(f"    {r['status']:7} {r['handle']}  {r['ps_product_id']}")
    if len(missing) > 40:
        print(f"    … {len(missing) - 40} more (see --csv)")

    if args.csv:
        with open(args.csv, "w", newline="", encoding="utf-8") as out:
            writer = csv.DictWriter(out, fieldnames=list(rows[0].keys()) if rows else ["verdict"])
            writer.writeheader()
            writer.writerows(r for r in rows if r["verdict"] != "OK")
        print(f"wrote {args.csv}")

    if not args.apply:
        return 0
    failed = 0
    for i in range(0, len(missing), 25):
        batch = missing[i:i + 25]
        data = graphql(store, version, token, METAFIELDS_SET, {"metafields": [
            {"ownerId": r["id"], "namespace": "trophy_sync", "key": "vendor",
             "type": "single_line_text_field", "value": args.vendor} for r in batch]})
        errors = data["metafieldsSet"]["userErrors"]
        if errors:
            failed += len(batch)
            print(f"  ! batch {i // 25 + 1}: {errors}")
    print(f"vendor={args.vendor} written on {len(missing) - failed} of {len(missing)}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
