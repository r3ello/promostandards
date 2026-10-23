"""Read one listing back: variants, options, ids, stock and its media ids (to see the photo was kept)."""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from check_discount_metafield import access_token, graphql, local_credentials, report_errors

Q = """query($q: String!) { products(first: 2, query: $q) { nodes { handle title status options { name values }
  ids: metafield(namespace: "custom", key: "ps_product_ids") { value }
  media(first: 10) { nodes { id status } }
  variants(first: 10) { nodes { sku price vs: metafield(namespace: "trophy_sync", key: "vendor_sku") { value }
    d: metafield(namespace: "trophy_discount", key: "discount_tiers") { value }
    inventoryItem { tracked inventoryLevels(first: 3) { nodes { location { id } quantities(names: ["available"]) { quantity } } } } } } } } }"""
store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)
p = graphql(store, version, token, Q, {"q": f"handle:{sys.argv[1]}"})
report_errors("read", p)
print(json.dumps([n for n in p["data"]["products"]["nodes"] if n["handle"] == sys.argv[1]], indent=1))
