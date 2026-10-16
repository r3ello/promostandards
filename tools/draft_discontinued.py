"""Unpublish store products PaceSetter no longer has: set them to DRAFT and tag them, never delete.

The input is a list of store handles (a CSV with a `handle` column, `;`-separated, or a .txt with one
handle per line) - normally `descatalogados-activos.csv` from a `simulate_store_sync.py` run, after
every id of each product was re-checked live as gone from PaceSetter.

Each product gets status DRAFT (`productUpdate`) and the tag (`tagsAdd`, which keeps the product's
other tags), so the admin can filter exactly these drafts apart from any other. Orders, URLs,
metafields and variants are untouched; undoing it is setting the status back to ACTIVE.

Dry run by default: it looks every handle up and prints what it would do. `--apply` writes.

Usage:
    python tools/draft_discontinued.py reports/sync-simulation-2026-10-13/descatalogados-activos.csv
    python tools/draft_discontinued.py <list> --apply --report <run>.jsonl
    python tools/draft_discontinued.py <list> --tag pacesetter-discontinued

Requires: application-local.yaml Shopify credentials (write_products).
Origin: 2026-10-13, 141 active products whose 271 PaceSetter ids no longer answer anything.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_discount_metafield import access_token, graphql, local_credentials  # noqa: E402

LOOKUP = """
query Lookup($handle: String!) {
  productByIdentifier(identifier: { handle: $handle }) { id handle title status tags }
}
"""

TO_DRAFT = """
mutation ToDraft($id: ID!) {
  productUpdate(product: { id: $id, status: DRAFT }) {
    product { id status }
    userErrors { field message }
  }
}
"""

TAG = """
mutation Tag($id: ID!, $tags: [String!]!) {
  tagsAdd(id: $id, tags: $tags) {
    node { id }
    userErrors { field message }
  }
}
"""


def read_handles(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8-sig")
    if path.suffix.lower() == ".csv":
        return [row["handle"].strip() for row in csv.DictReader(text.splitlines(), delimiter=";")
                if row.get("handle", "").strip()]
    return [line.strip() for line in text.splitlines() if line.strip() and not line.startswith("#")]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("handles", type=Path, help="CSV with a handle column, or .txt one per line")
    parser.add_argument("--tag", default="pacesetter-discontinued")
    parser.add_argument("--apply", action="store_true", help="write; without it nothing changes")
    parser.add_argument("--report", type=Path, help="JSONL, one line per product")
    args = parser.parse_args()

    store, client_id, secret, version = local_credentials()
    token = access_token(store, client_id, secret)
    handles = read_handles(args.handles)
    report = args.report.open("a", encoding="utf-8") if args.report else None
    counts: dict[str, int] = {}

    print(f"{'APPLY' if args.apply else 'DRY RUN'}: {len(handles)} handles -> DRAFT + tag '{args.tag}'")
    for handle in handles:
        found = graphql(store, version, token, LOOKUP, {"handle": handle})
        product = (found.get("data") or {}).get("productByIdentifier")
        if found.get("errors") or not product:
            outcome, detail = "NOT_FOUND", found.get("errors")
        else:
            needs_status = product["status"] != "DRAFT"
            needs_tag = args.tag not in product["tags"]
            if not needs_status and not needs_tag:
                outcome, detail = "ALREADY_DONE", None
            elif not args.apply:
                outcome, detail = "WOULD_CHANGE", {"status": product["status"], "tag": needs_tag}
            else:
                errors = []
                if needs_status:
                    r = graphql(store, version, token, TO_DRAFT, {"id": product["id"]})
                    errors += r.get("errors") or ((r.get("data") or {}).get("productUpdate") or {}).get("userErrors") or []
                if needs_tag:
                    r = graphql(store, version, token, TAG, {"id": product["id"], "tags": [args.tag]})
                    errors += r.get("errors") or ((r.get("data") or {}).get("tagsAdd") or {}).get("userErrors") or []
                outcome, detail = ("FAILED", errors) if errors else ("CHANGED", {"was": product["status"]})
        counts[outcome] = counts.get(outcome, 0) + 1
        title = product["title"] if product else ""
        print(f"  {outcome:13} {handle}  {title[:60]}" + (f"  {detail}" if outcome in ("FAILED", "NOT_FOUND") else ""))
        if report:
            report.write(json.dumps({"at": datetime.now(timezone.utc).isoformat(), "handle": handle,
                                     "id": product and product["id"], "outcome": outcome,
                                     "detail": detail}, ensure_ascii=False) + "\n")
    if report:
        report.close()
    print("summary:", counts)
    return 1 if counts.get("FAILED") or counts.get("NOT_FOUND") else 0


if __name__ == "__main__":
    sys.exit(main())
