"""Snapshot (or compare) the two state products: per vendor_sku the variant id, SKU, price, discount, option values.

usage: python reports/fusion-2-2026-10-09/snap_states.py <out.json> [<before.json> to compare]
"""
import json, sys
sys.path.insert(0, "tools")
from check_discount_metafield import local_credentials, access_token, graphql

HANDLES = ["p-5805-alaska-state-silhouette-awards", "p-7997-frosted-acrylic-ak-state-cutout-on-black-plaque"]
Q = """query($h: String!) { productByHandle(handle: $h) { options { name values }
  variants(first: 100) { nodes { id sku price inventoryPolicy selectedOptions { name value }
    v: metafield(namespace: "trophy_sync", key: "vendor_sku") { value }
    d: metafield(namespace: "trophy_discount", key: "discount_tiers") { value } } } } }"""
store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)
snap = {}
for h in HANDLES:
    p = graphql(store, version, token, Q, {"h": h})["data"]["productByHandle"]
    snap[h] = {"options": p["options"], "variants": {
        n["v"]["value"]: {"id": n["id"], "sku": n["sku"], "price": n["price"], "discount": n["d"] and n["d"]["value"],
                          "options": " / ".join(o["value"] for o in n["selectedOptions"])}
        for n in p["variants"]["nodes"]}}
json.dump(snap, open(sys.argv[1], "w"), indent=1)
if len(sys.argv) > 2:
    before = json.load(open(sys.argv[2]))
    for h in HANDLES:
        b, a = before[h]["variants"], snap[h]["variants"]
        print(h)
        print("  options:", [(o["name"], len(o["values"]), o["values"][:4], o["values"][-3:]) for o in snap[h]["options"]])
        print("  variants before/after:", len(b), len(a), "| same set of parts:", set(b) == set(a))
        for field in ("id", "sku", "price", "discount"):
            diff = [k for k in b if k in a and b[k][field] != a[k][field]]
            print(f"  {field} changed: {len(diff)}", diff[:5])
        for k in sorted(a)[:3] + sorted(a)[-2:]:
            print(f"    {k}: {b.get(k, {}).get('options')}  ->  {a[k]['options']}")
