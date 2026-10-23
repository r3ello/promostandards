"""Repair after the first sync attempt: the listings it touched were left tracked with 0 in stock.

The sync turned inventory tracking on (PaceSetter gives a quantity) but its quantity write went to a
deleted location (application-local.yaml still had the dev location-id), so they were sold out.
Before the sync they were untracked, i.e. sellable; this puts exactly that back: tracked=false on
every variant of the 53 CD900* listings that carries trophy_sync.vendor_sku and is tracked.

    python reports/ungroup-cd900-2026-10-23/untrack.py           # what it would do
    python reports/ungroup-cd900-2026-10-23/untrack.py --apply
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "tools"))
from check_discount_metafield import access_token, graphql, local_credentials, report_errors  # noqa: E402

READ = """
query($q: String!) { products(first: 5, query: $q) { nodes { handle
  variants(first: 5) { nodes { sku inventoryQuantity
    vendorSku: metafield(namespace: "trophy_sync", key: "vendor_sku") { value }
    inventoryItem { id tracked } } } } } }
"""
UNTRACK = """
mutation($id: ID!) { inventoryItemUpdate(id: $id, input: {tracked: false}) { userErrors { field message } } }
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    snap = json.loads((HERE / "before.json").read_text(encoding="utf-8"))
    handles = [snap["survivor"]["handle"], *snap["archived"]]
    store, cid, secret, version = local_credentials()
    token = access_token(store, cid, secret)
    todo = []
    for h in handles:
        p = graphql(store, version, token, READ, {"q": f"handle:{h}"})
        report_errors(h, p)
        for n in p["data"]["products"]["nodes"]:
            if n["handle"] != h:
                continue
            for v in n["variants"]["nodes"]:
                if v["vendorSku"] and v["inventoryItem"]["tracked"] and (v["inventoryQuantity"] or 0) <= 0:
                    todo.append((v["sku"], v["inventoryItem"]["id"]))
    print(f"{len(todo)} tracked variant(s) at 0: {[s for s, _ in todo]}")
    if not args.apply:
        print("dry run: nothing written (--apply)")
        return 0
    failed = 0
    for sku, item in todo:
        r = graphql(store, version, token, UNTRACK, {"id": item})
        errs = report_errors(sku, r) and not ((r.get("data") or {}).get("inventoryItemUpdate") or {}).get("userErrors")
        failed += 0 if errs else 1
        print(f"  {'ok' if errs else 'FAILED'}: {sku}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
