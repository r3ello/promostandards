"""Sync every PromoStandards product that is already in the Shopify store, one at a time.

The unit is the STORE PRODUCT, not the supplier id: a migrated product covers several PaceSetter ids
(`custom.ps_product_ids`) and syncing its canonical id (`custom.ps_product_id`) covers all of them in
one pass. Driving it per supplier id would sync the same product a dozen times.

Each product goes through the app's own endpoint - `POST /api/sync/products/{id}` - so this is
exactly what the console button does: variants (a migrated product gets its real variants, adopting
the legacy one), inventory, prices, and the quantity-discount metafield in the same call. Nothing
here talks to Shopify except to list what to sync.

It is sequential on purpose (the supplier is the bottleneck and the app already fans out per product)
and every result is appended to a JSONL report as it happens, so a run that dies half way loses
nothing: re-run with --skip-done <report> and it carries on.

Usage:
    python tools/sync_store_catalog.py --dry-run                  # how many, and which
    python tools/sync_store_catalog.py --report run.jsonl
    python tools/sync_store_catalog.py --report run.jsonl --skip-done run.jsonl   # resume
    python tools/sync_store_catalog.py --limit 5                  # a careful first slice

Credentials: the app's login from application-local.yaml (same file the app reads), and for the
store listing the client_credentials grant, both via check_discount_metafield.py.

Requires: standard library only.
Origin: 2026-09-03, first full pass over the migrated catalogue.
"""

from __future__ import annotations

import argparse
import http.cookiejar
import json
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_discount_metafield import LOCAL_YAML, access_token, graphql, local_credentials  # noqa: E402

# The app's own listing document, kept in step with ShopifyGraphQL.IMPORTED_PRODUCTS.
STORE_PRODUCTS = """
query ImportedProducts($cursor: String) {
  products(first: 100, after: $cursor, query: "tag:promostandards") {
    pageInfo { hasNextPage endCursor }
    nodes {
      handle
      psId: metafield(namespace: "custom", key: "ps_product_id") { value }
      psIds: metafield(namespace: "custom", key: "ps_product_ids") { value }
      lastSync: metafield(namespace: "trophy_sync", key: "last_sync_at") { value }
      media(first: 60) { nodes { id } }
      variants(first: 100) { nodes { id } }
    }
  }
}
"""


def app_credentials() -> tuple[str, str]:
    block = LOCAL_YAML.read_text(encoding="utf-8")
    block = block[block.index("security:"):]
    user = re.search(r'username:\s*"?([^"\n]+)"?', block).group(1).strip()
    password = re.search(r'password:\s*"?([^"\n]+)"?', block).group(1).strip()
    return user, password


def opener(base: str):
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    user, password = app_credentials()
    op.open(urllib.request.Request(f"{base}/api/auth/login",
                                   data=json.dumps({"username": user, "password": password}).encode(),
                                   headers={"Content-Type": "application/json"}), timeout=60)
    return op


def store_products() -> list[dict]:
    """Every tagged store product: its canonical supplier id and how many ids it covers."""
    shop, client_id, client_secret, version = local_credentials()
    token = access_token(shop, client_id, client_secret)
    products, cursor = [], None
    while True:
        payload = graphql(shop, version, token, STORE_PRODUCTS, {"cursor": cursor} if cursor else {})
        if payload.get("errors"):
            sys.exit(f"listing failed: {payload['errors']}")
        page = payload["data"]["products"]
        for node in page["nodes"]:
            canonical = (node.get("psId") or {}).get("value")
            covered = (node.get("psIds") or {}).get("value")
            try:
                covered_ids = json.loads(covered) if covered else []
            except json.JSONDecodeError:
                covered_ids = []
            if not canonical and covered_ids:
                canonical = covered_ids[0]
            if canonical:
                # Canonical first: it is the id the product is "about", and the one to drive it by
                # whenever the supplier still sells it.
                ids = [canonical.strip()] + [i.strip() for i in covered_ids
                                             if i.strip().upper() != canonical.strip().upper()]
                products.append({"productId": canonical.strip(), "handle": node["handle"],
                                 "ids": ids, "covers": len(ids),
                                 "synced": bool((node.get("lastSync") or {}).get("value")),
                                 "images": len(node["media"]["nodes"]),
                                 "variants": len(node["variants"]["nodes"])})
        if not page["pageInfo"]["hasNextPage"]:
            return products
        cursor = page["pageInfo"]["endCursor"]


def sellable_ids(base: str) -> set[str]:
    """What the supplier still sells, straight from the app's own catalog list (one cheap call)."""
    op = opener(base)
    catalog = json.load(op.open(f"{base}/api/catalog/products", timeout=600))
    return {row["productId"].strip().upper() for row in catalog if row.get("productId")}


def sync_one(op, base: str, product_id: str, timeout: int) -> dict:
    request = urllib.request.Request(f"{base}/api/sync/products/{urllib.parse.quote(product_id)}",
                                     data=b"", method="POST",
                                     headers={"Content-Type": "application/json"})
    started = time.time()
    try:
        body = json.load(op.open(request, timeout=timeout))
        discounts = body.get("discounts") or {}
        return {
            "productId": product_id, "ok": True, "seconds": round(time.time() - started, 1),
            "variants": body.get("variantCount"), "inventoryUpdated": body.get("inventoryUpdated"),
            "discountOutcome": discounts.get("outcome"), "discountReason": discounts.get("reason"),
            "discountVariants": sum(len(v.get("variantGids") or []) for v in discounts.get("variants") or []),
            "discountError": body.get("discountError"),
        }
    except urllib.error.HTTPError as e:
        return {"productId": product_id, "ok": False, "seconds": round(time.time() - started, 1),
                "error": f"HTTP {e.code}: {e.read().decode(errors='replace')[:300]}"}
    except Exception as e:  # timeouts, resets - one product must not end the run
        return {"productId": product_id, "ok": False, "seconds": round(time.time() - started, 1),
                "error": f"{type(e).__name__}: {e}"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--report", default="", help="JSONL file, appended as each product finishes")
    parser.add_argument("--skip-done", default="", help="a previous report: its successes are skipped")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--timeout", type=int, default=900, help="per product, seconds")
    parser.add_argument("--only-sellable", action="store_true",
                        help="skip products the supplier no longer sells, and drive each one by the "
                             "id it DOES sell (a store product whose canonical id is gone may still "
                             "cover a sibling that is not)")
    parser.add_argument("--synced-only", action="store_true",
                        help="only products this app has already put in the store (they carry "
                             "trophy_sync.last_sync_at) — for pushing a new field to what is live "
                             "without importing anything new")
    parser.add_argument("--needs-images", action="store_true",
                        help="only products already synced that came out with fewer images than "
                             "variants — what a supplier media outage leaves behind, and what "
                             "--skip-done would otherwise skip for being 'ok'")
    parser.add_argument("--only", default="",
                        help="comma-separated supplier ids, or @file with one per line")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    products = store_products()
    if args.only_sellable:
        # 217 of the store's 298 tagged products point at ids PaceSetter has dropped: every one of
        # them answers "getProduct returned no product" instantly. Filtering them out here is not
        # cosmetic - it is the difference between a report of failures and a report of work.
        sellable = sellable_ids(args.base)
        kept = []
        for product in products:
            usable = next((i for i in product["ids"] if i.upper() in sellable), None)
            if usable:
                kept.append({**product, "productId": usable})
        print(f"{len(products) - len(kept)} product(s) dropped: the supplier sells none of their ids",
              flush=True)
        products = kept
    done = set()
    if args.skip_done and Path(args.skip_done).exists():
        for line in Path(args.skip_done).read_text(encoding="utf-8").splitlines():
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if row.get("ok"):
                done.add(row["productId"])
    if args.synced_only:
        products = [p for p in products if p.get("synced")]
        print(f"{len(products)} product(s) already synced by this app", flush=True)
    if args.needs_images:
        # PaceSetter serves one photo per product id, so a store product covering N ids should come
        # out with close to N images. Fewer means the media calls failed while the rest of the sync
        # succeeded: its Media service stops answering under load and the import is deliberately
        # tolerant of that, which leaves the product marked "ok" with nothing to show for it.
        # Compared against the ids it covers, never against variants — a product covering one id has
        # exactly one photo to get, however many variants the supplier splits it into.
        products = [p for p in products
                    if p.get("synced") and p["covers"] > 1 and p["images"] < p["covers"]]
        print(f"{len(products)} synced product(s) look short of images", flush=True)
    if args.only:
        wanted = (Path(args.only[1:]).read_text(encoding="utf-8").split()
                  if args.only.startswith("@") else args.only.split(","))
        wanted = {w.strip().upper() for w in wanted if w.strip()}
        products = [p for p in products
                    if p["productId"].upper() in wanted
                    or any(i.upper() in wanted for i in p["ids"])]

    todo = [p for p in products if p["productId"] not in done]
    if args.limit:
        todo = todo[:args.limit]

    print(f"{len(products)} store product(s) tagged promostandards; "
          f"{len(done)} already done; {len(todo)} to sync", flush=True)
    if args.dry_run:
        for p in todo[:20]:
            print(f"  {p['productId']:<12} {p['handle']} (covers {p['covers']} id(s))")
        if len(todo) > 20:
            print(f"  … and {len(todo) - 20} more")
        return 0

    report = Path(args.report) if args.report else None
    if report:
        # Do it before the first product, not after: the first run lost a sync to a missing directory.
        report.parent.mkdir(parents=True, exist_ok=True)
    op = opener(args.base)
    counts = {"ok": 0, "failed": 0, "published": 0, "no_discounts": 0, "not_written": 0}
    for i, product in enumerate(todo, 1):
        result = sync_one(op, args.base, product["productId"], args.timeout)
        counts["ok" if result["ok"] else "failed"] += 1
        outcome = (result.get("discountOutcome") or "").lower()
        if outcome in ("published", "no_discounts", "not_written"):
            counts[outcome] += 1
        if report:
            with report.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(result) + "\n")
        print(f"[{i}/{len(todo)}] {product['productId']:<12} "
              + (f"ok · {result['variants']} variants · inv {result['inventoryUpdated']} · "
                 f"discounts {result.get('discountOutcome')}"
                 f"{' (' + str(result['discountVariants']) + ' variants)' if result.get('discountVariants') else ''}"
                 if result["ok"] else f"FAILED · {result['error'][:160]}")
              + f" · {result['seconds']}s", flush=True)

    print(f"\ndone: {counts['ok']} ok, {counts['failed']} failed · discounts: "
          f"{counts['published']} published, {counts['no_discounts']} none to publish, "
          f"{counts['not_written']} not written", flush=True)
    return 1 if counts["failed"] else 0


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  (only needed inside sync_one)
    sys.exit(main())
