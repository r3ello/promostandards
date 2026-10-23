"""Read-only snapshot of the CD900* states fusion, before undoing it.

Reads the surviving product (p-5805), the 52 archived listings (the 51 of the states batch and
p-7116 USA from the leftovers), the redirects pointing at p-5805, and the orders that bought one of
the survivor's variants. Writes nothing to the store.

Usage:
    python reports/ungroup-cd900-2026-10-23/snap.py reports/ungroup-cd900-2026-10-23/before.json
"""

from __future__ import annotations

import csv
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from check_discount_metafield import access_token, graphql, local_credentials, report_errors  # noqa: E402

FUSION = ROOT / "reports" / "fusion-2-2026-10-09"
SURVIVOR = "p-5805-alaska-state-silhouette-awards"

PRODUCT = """
query($q: String!) { products(first: 5, query: $q) { nodes {
  id handle title status totalInventory
  options { name values }
  psId: metafield(namespace: "custom", key: "ps_product_id") { value }
  psIds: metafield(namespace: "custom", key: "ps_product_ids") { value }
  groupedInto: metafield(namespace: "trophy_sync", key: "grouped_into") { value }
  groupedIds: metafield(namespace: "trophy_sync", key: "grouped_ids") { value }
  keepImages: metafield(namespace: "trophy_sync", key: "keep_store_images") { value }
  discount: metafield(namespace: "trophy_discount", key: "discount_tiers") { value }
  media(first: 1) { nodes { id } }
  variants(first: 100) { nodes {
    id sku title price inventoryQuantity
    inventoryItem { tracked }
    selectedOptions { name value }
    vendorSku: metafield(namespace: "trophy_sync", key: "vendor_sku") { value }
    promoId: metafield(namespace: "custom", key: "promo_standard_id") { value }
    discount: metafield(namespace: "trophy_discount", key: "discount_tiers") { value }
  } }
} } }
"""


ORDERS = """
query($q: String!, $after: String) { orders(first: 50, after: $after, query: $q) {
  pageInfo { hasNextPage endCursor }
  nodes { name createdAt displayFulfillmentStatus
    lineItems(first: 50) { nodes { name sku quantity variant { id } } } } } }
"""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


_OPENER = urllib.request.build_opener(_NoRedirect)


def http_answer(store: str, handle: str) -> str:
    """'301 -> /products/…' for a redirect, '200' for a live page, else the status."""
    try:
        with _OPENER.open(urllib.request.Request(f"https://{store}/products/{handle}", method="HEAD"),
                          timeout=20) as r:
            return str(r.status)
    except urllib.error.HTTPError as e:
        loc = e.headers.get("Location") or ""
        return f"{e.code} -> {urllib.parse.urlparse(loc).path}" if loc else str(e.code)


def archived_handles() -> list[str]:
    handles = []
    for name in ("2_products-archive-states.csv", "2_products-archive-leftovers.csv"):
        with open(FUSION / name, encoding="utf-8-sig", newline="") as f:
            for row in csv.DictReader(f):
                into = next((v for k, v in row.items() if "grouped_into" in k), "")
                if into == SURVIVOR:
                    handles.append(row["Handle"])
    return handles


def product(store, version, token, handle):
    p = graphql(store, version, token, PRODUCT, {"q": f"handle:{handle}"})
    report_errors(handle, p)
    nodes = [n for n in p["data"]["products"]["nodes"] if n["handle"] == handle]
    return nodes[0] if nodes else None


def paged(store, version, token, query, field, q):
    out, after = [], None
    while True:
        p = graphql(store, version, token, query, {"q": q, "after": after})
        report_errors(field, p)
        d = p["data"][field]
        out += d["nodes"]
        if not d["pageInfo"]["hasNextPage"]:
            return out
        after = d["pageInfo"]["endCursor"]


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    store, cid, secret, version = local_credentials()
    token = access_token(store, cid, secret)
    survivor = product(store, version, token, SURVIVOR)
    if survivor is None:
        print(f"{SURVIVOR} not found on {store}")
        return 1
    handles = archived_handles()
    archived = {h: product(store, version, token, h) for h in handles}
    # The app holds no read_online_store_navigation, so redirects are seen over HTTP, as on 2026-10-09.
    redirects = [{"path": f"/products/{h}", "answer": http_answer(store, h)} for h in handles]
    variant_ids = {v["id"] for v in survivor["variants"]["nodes"]}
    orders = []
    for o in paged(store, version, token, ORDERS, "orders", "created_at:>=2026-10-09"):
        lines = [l for l in o["lineItems"]["nodes"] if (l.get("variant") or {}).get("id") in variant_ids]
        if lines:
            orders.append({"name": o["name"], "createdAt": o["createdAt"],
                           "fulfillment": o["displayFulfillmentStatus"], "lines": lines})
    snap = {"store": store, "survivor": survivor, "archived": archived, "redirects": redirects,
            "orders": orders}
    Path(sys.argv[1]).write_text(json.dumps(snap, indent=1, ensure_ascii=False), encoding="utf-8")

    vs = survivor["variants"]["nodes"]
    print(f"{store}: {SURVIVOR} {survivor['status']}, {len(vs)} variants, options "
          f"{[o['name'] for o in survivor['options']]}, ps_product_ids "
          f"{len(json.loads(survivor['psIds']['value'])) if survivor['psIds'] else 0}")
    missing = [h for h, p in archived.items() if p is None]
    print(f"archived listings: {len(handles)} expected, {len(handles) - len(missing)} found, missing {missing}")
    states = {}
    for p in archived.values():
        if p:
            states[(p["status"], len(p["variants"]["nodes"]), bool(p["psId"]), bool(p["groupedInto"]))] = \
                states.get((p["status"], len(p["variants"]["nodes"]), bool(p["psId"]), bool(p["groupedInto"])), 0) + 1
    print("  (status, variants, has ps_product_id, has grouped_into):", states)
    answers = {}
    for r in redirects:
        a = r["answer"].replace(SURVIVOR, "<survivor>")
        answers[a] = answers.get(a, 0) + 1
    print(f"archived URLs answer: {answers}")
    print(f"orders since 2026-10-09 on its variants: {[(o['name'], [l['sku'] for l in o['lines']]) for o in orders]}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
