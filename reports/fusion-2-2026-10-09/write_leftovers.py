"""Matrixify CSVs that finish the states merge with the listings leftovers.py found (suffix -leftovers).

1_ adds the leftover codes to the kept product's ps_product_ids (read live, then extended);
2_ archives the leftovers with the same grouped_into/grouped_ids trail; 3_ redirects them.

usage: python reports/fusion-2-2026-10-09/write_leftovers.py reports/fusion-2-2026-10-09
"""
import csv, json, sys
sys.path.insert(0, "tools")
from check_discount_metafield import local_credentials, access_token, graphql

D = sys.argv[1]
L = json.load(open(f"{D}/leftovers.json"))
store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)
MF_IDS = "Metafield: custom.ps_product_ids [list.single_line_text_field]"
MF_ID = "Metafield: custom.ps_product_id [single_line_text_field]"
MF_INTO = "Metafield: trophy_sync.grouped_into [single_line_text_field]"
MF_GIDS = "Metafield: trophy_sync.grouped_ids [list.single_line_text_field]"

keep, archive, redirect = [], [], []
for h in sorted({o["into"][0] for o in L}):
    p = graphql(store, version, token, """query($h: String!) { productByHandle(handle: $h) {
        a: metafield(namespace: "custom", key: "ps_product_ids") { value } } }""", {"h": h})["data"]["productByHandle"]
    ids = json.loads(p["a"]["value"])
    ids += [i for o in L if o["into"][0] == h for i in o["ids"] if i not in ids]
    keep.append({"Handle": h, "Command": "MERGE", MF_IDS: ",".join(ids)})
for o in L:
    archive.append({"Handle": o["handle"], "Command": "MERGE", "Status": "Archived", MF_ID: "", MF_IDS: "",
                    MF_INTO: o["into"][0], MF_GIDS: ",".join(o["ids"])})
    redirect.append({"Path": "/products/" + o["handle"], "Command": "MERGE", "Target": "/products/" + o["into"][0]})


def w(name, rows):
    with open(f"{D}/{name}", "w", newline="", encoding="utf-8") as f:
        d = csv.DictWriter(f, fieldnames=list(rows[0]))
        d.writeheader()
        d.writerows(rows)


w("1_products-keep-leftovers.csv", keep)
w("2_products-archive-leftovers.csv", archive)
w("3_redirects-leftovers.csv", redirect)
print("keep", len(keep), "archive", len(archive), "redirects", len(redirect))
for k in keep:
    print(k["Handle"], len(k[MF_IDS].split(",")), "ids")
