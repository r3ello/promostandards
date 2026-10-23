"""Step 1 of undoing the CD900* states fusion: p-5805 goes back to being Alaska alone.

Deletes its 52 variants of other states (the AK one keeps its id, SKU PS6487-AK and history), turns
its State option back into Title / Default Title (what a one-variant product is, and what the sync
expects of one — it can only undo a Color option itself), sets custom.ps_product_ids to [CD900AK]
and gives it back the title it had before the fusion. Run it BEFORE the unarchive CSV: until then
the archived listings carry no ps_product_id, so no supplier id is ever claimed by two products.

Dry run by default: prints what it would do and writes nothing until --apply. Refuses unless the
product is exactly as snap.py saw it (53 variants, State option, one CD900AK variant).

    python reports/ungroup-cd900-2026-10-23/1_alaska.py            # what it would do
    python reports/ungroup-cd900-2026-10-23/1_alaska.py --apply    # do it
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
from check_discount_metafield import access_token, graphql, local_credentials, report_errors  # noqa: E402

HANDLE = "p-5805-alaska-state-silhouette-awards"
TITLE = "Alaska State Silhouette Awards"   # 0_review-states.csv of the fusion, "Current title"
KEEP = "CD900AK"
EXPECTED_VARIANTS = 53

READ = """
query($q: String!) { products(first: 5, query: $q) { nodes {
  id handle title
  options { id name optionValues { id name } }
  variants(first: 100) { nodes { id sku vendorSku: metafield(namespace: "trophy_sync", key: "vendor_sku") { value } } }
} } }
"""
DELETE = """
mutation($productId: ID!, $variantsIds: [ID!]!) {
  productVariantsBulkDelete(productId: $productId, variantsIds: $variantsIds) { userErrors { field message } } }
"""
OPTION = """
mutation($productId: ID!, $option: OptionUpdateInput!, $optionValuesToUpdate: [OptionValueUpdateInput!], $variantStrategy: ProductOptionUpdateVariantStrategy) {
  productOptionUpdate(productId: $productId, option: $option, optionValuesToUpdate: $optionValuesToUpdate, variantStrategy: $variantStrategy) {
    userErrors { field message } } }
"""
METAFIELDS = """
mutation($metafields: [MetafieldsSetInput!]!) { metafieldsSet(metafields: $metafields) { userErrors { field message } } }
"""
TITLE_UPDATE = """
mutation($product: ProductUpdateInput!) { productUpdate(product: $product) { userErrors { field message } } }
"""


def run(store, version, token, label, query, variables, field) -> bool:
    p = graphql(store, version, token, query, variables)
    ok = report_errors(label, p)
    for e in ((p.get("data") or {}).get(field) or {}).get("userErrors") or []:
        print(f"  ! {label}: {e.get('message')}")
        ok = False
    print(f"  {'ok' if ok else 'FAILED'}: {label}")
    return ok


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--apply", action="store_true", help="write (default: only show)")
    args = parser.parse_args()
    store, cid, secret, version = local_credentials()
    token = access_token(store, cid, secret)
    p = graphql(store, version, token, READ, {"q": f"handle:{HANDLE}"})
    report_errors("read", p)
    nodes = [n for n in p["data"]["products"]["nodes"] if n["handle"] == HANDLE]
    if not nodes:
        print(f"{HANDLE} not found on {store}")
        return 1
    prod = nodes[0]
    variants = prod["variants"]["nodes"]
    keep = [v for v in variants if (v["vendorSku"] or {}).get("value") == KEEP]
    state = [o for o in prod["options"] if o["name"] == "State"]
    if len(variants) != EXPECTED_VARIANTS or len(keep) != 1 or len(state) != 1 or len(prod["options"]) != 1:
        print(f"refusing: {HANDLE} is not as snapshotted ({len(variants)} variants, {len(keep)} {KEEP}, "
              f"options {[o['name'] for o in prod['options']]})")
        return 1
    gone = [v for v in variants if v is not keep[0]]
    alaska = next(v for v in state[0]["optionValues"] if v["name"] == "Alaska")
    print(f"{store}: {HANDLE} ({prod['id']})")
    print(f"  delete {len(gone)} variants: {', '.join(v['sku'] for v in gone)}")
    print(f"  keep {keep[0]['sku']} ({keep[0]['id']})")
    print(f"  option State/Alaska -> Title/Default Title; ps_product_ids -> [{KEEP}]; title -> {TITLE!r}")
    if not args.apply:
        print("dry run: nothing written (--apply to do it)")
        return 0
    gid = prod["id"]
    ok = run(store, version, token, f"delete {len(gone)} variants", DELETE,
             {"productId": gid, "variantsIds": [v["id"] for v in gone]}, "productVariantsBulkDelete")
    # Renaming only once the other values are gone: one value is what Title / Default Title means.
    ok = ok and run(store, version, token, "option -> Title / Default Title", OPTION,
                    {"productId": gid, "option": {"id": state[0]["id"], "name": "Title"},
                     "optionValuesToUpdate": [{"id": alaska["id"], "name": "Default Title"}],
                     "variantStrategy": "LEAVE_AS_IS"}, "productOptionUpdate")
    ok = run(store, version, token, "ps_product_ids", METAFIELDS,
             {"metafields": [{"ownerId": gid, "namespace": "custom", "key": "ps_product_ids",
                              "type": "list.single_line_text_field", "value": json.dumps([KEEP])}]},
             "metafieldsSet") and ok
    ok = run(store, version, token, "title", TITLE_UPDATE, {"product": {"id": gid, "title": TITLE}},
             "productUpdate") and ok
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
