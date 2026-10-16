"""Was the Matrixify load applied? Per review row: role, live status, title, ps_product_ids, grouped_into.

usage: python reports/fusion-2-2026-10-09/check_loaded.py reports/fusion-2-2026-10-09
"""
import csv, glob, sys, collections
sys.path.insert(0, "tools")
from check_discount_metafield import local_credentials, access_token, graphql

D = sys.argv[1]
store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)
Q = """query($h: String!) { productByHandle(handle: $h) { status title variantsCount { count }
  a: metafield(namespace: "custom", key: "ps_product_ids") { value }
  g: metafield(namespace: "trophy_sync", key: "grouped_into") { value } } }"""
bad = 0
for f in sorted(glob.glob(f"{D}/0_review*.csv")):
    tally = collections.Counter()
    for r in csv.DictReader(open(f, encoding="utf-8-sig")):
        p = graphql(store, version, token, Q, {"h": r["Handle"]}).get("data", {}).get("productByHandle")
        if not p:
            print("MISSING", r["Handle"]); bad += 1; continue
        if r["Role"] == "KEEP":
            ok = p["title"] == r["New title"] and p["status"] == r.get("Status", "ACTIVE") and p["a"] is not None
        else:
            ok = p["status"] == "ARCHIVED" and p["a"] is None and p["g"] is not None
        tally[(r["Role"], ok)] += 1
        if not ok:
            bad += 1
            print("  NOT APPLIED", r["Role"], r["Handle"], p["status"], p["title"], p["a"] and p["a"]["value"])
    print(f, dict(tally))
sys.exit(1 if bad else 0)
