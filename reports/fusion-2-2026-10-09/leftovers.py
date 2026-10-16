"""Listings left behind by the merge: not archived, carrying a code that is already a variant of a kept product.

Reads the kept handles from the 0_review*.csv files, their variants' vendor_sku live, and every tagged
store product's ps_product_id(s) live. Writes leftovers.json and prints them.

usage: python reports/fusion-2-2026-10-09/leftovers.py reports/fusion-2-2026-10-09
"""
import csv, glob, json, sys
sys.path.insert(0, "tools")
from check_discount_metafield import local_credentials, access_token, graphql

D = sys.argv[1]
keep = {r["Handle"]: r for f in glob.glob(f"{D}/0_review*.csv")
        for r in csv.DictReader(open(f, encoding="utf-8-sig")) if r["Role"] == "KEEP"}
store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)

owner = {}
for h in keep:
    p = graphql(store, version, token, """query($h: String!) { productByHandle(handle: $h) {
        variants(first: 100) { nodes { v: metafield(namespace: "trophy_sync", key: "vendor_sku") { value } } } } }""",
                {"h": h})["data"]["productByHandle"]
    for n in p["variants"]["nodes"]:
        if n["v"]:
            owner[n["v"]["value"].upper()] = h

Q = """query($after: String) { products(first: 250, after: $after, query: "tag:promostandards") {
  pageInfo { hasNextPage endCursor } nodes { handle title status
    a: metafield(namespace: "custom", key: "ps_product_ids") { value }
    b: metafield(namespace: "custom", key: "ps_product_id") { value } } } }"""
after, out = None, []
while True:
    d = graphql(store, version, token, Q, {"after": after})["data"]["products"]
    for n in d["nodes"]:
        if n["status"] == "ARCHIVED" or n["handle"] in keep:
            continue
        raw = (n["a"] or n["b"] or {}).get("value") or ""
        ids = [i.strip('[]" ').upper() for i in raw.split(",") if i.strip('[]" ')]
        hit = sorted({owner[i] for i in ids if i in owner})
        if hit:
            out.append({"handle": n["handle"], "title": n["title"], "status": n["status"], "ids": ids,
                        "into": hit, "covered": [i for i in ids if i in owner]})
    if not d["pageInfo"]["hasNextPage"]:
        break
    after = d["pageInfo"]["endCursor"]
json.dump(out, open(f"{D}/leftovers.json", "w"), indent=1)
for o in out:
    print(o["status"], o["handle"], o["ids"], "->", o["into"], "" if len(o["covered"]) == len(o["ids"]) else "PARTIAL")
print("leftovers", len(out))
