"""Check the listings of the review CSVs against the live store: status, ps_product_ids, variant count.

usage: python reports/fusion-2-2026-10-09/verify_live.py reports/fusion-2-2026-10-09
"""
import csv, glob, sys
sys.path.insert(0, "tools")
from check_discount_metafield import local_credentials, access_token, graphql

D = sys.argv[1]
rows = [r for f in glob.glob(f"{D}/0_review*.csv") for r in csv.DictReader(open(f, encoding="utf-8-sig"))]
store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)
Q = """query($h: String!) { productByHandle(handle: $h) { status variantsCount { count }
  a: metafield(namespace: "custom", key: "ps_product_ids") { value }
  b: metafield(namespace: "custom", key: "ps_product_id") { value } } }"""
bad = 0
for r in rows:
    p = graphql(store, version, token, Q, {"h": r["Handle"]}).get("data", {}).get("productByHandle")
    if not p:
        print("MISSING", r["Handle"]); bad += 1; continue
    ids = p["a"]["value"] if p["a"] else (p["b"]["value"] if p["b"] else "")
    ids = " ".join(i.strip('[]" ') for i in ids.split(","))
    if p["status"] != r["Status"] or ids.split() != r["Current ps_product_ids"].split() or p["variantsCount"]["count"] != 1:
        print("CHANGED", r["Handle"], p["status"], ids, p["variantsCount"]["count"]); bad += 1
print("checked", len(rows), "store", store, "differences", bad)
sys.exit(1 if bad else 0)
