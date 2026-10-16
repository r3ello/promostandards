"""After the sync: read each kept listing back from the store, and check the redirects over HTTP.

Per survivor: status, title, variant count, how many variants carry trophy_sync.vendor_sku and the
discount metafield, options, media count; per archived handle: 301 to the survivor.

usage: python reports/fusion-2-2026-10-09/verify_synced.py reports/fusion-2-2026-10-09 [suffix]
"""
import csv, sys, urllib.request, urllib.error
sys.path.insert(0, "tools")
from check_discount_metafield import local_credentials, access_token, graphql

D, SFX = sys.argv[1], (sys.argv[2] if len(sys.argv) > 2 else "")
rows = list(csv.DictReader(open(f"{D}/0_review{SFX}.csv", encoding="utf-8-sig")))
store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)
HOST = "https://" + store
Q = """query($h: String!) { productByHandle(handle: $h) { status title onlineStoreUrl
  options { name values } media(first: 100) { nodes { status } }
  variants(first: 100) { nodes { sku price inventoryQuantity
    v: metafield(namespace: "trophy_sync", key: "vendor_sku") { value }
    d: metafield(namespace: "trophy_discount", key: "discount_tiers") { value } } } } }"""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


opener = urllib.request.build_opener(NoRedirect)


def redirect(handle):
    try:
        r = opener.open(urllib.request.Request(f"{HOST}/products/{handle}", method="GET", headers={"User-Agent": "Mozilla/5.0"}), timeout=20)
        return r.status, ""
    except urllib.error.HTTPError as e:
        return e.code, (e.headers.get("Location") or "").replace(HOST, "")
    except Exception as e:  # network
        return 0, str(e)[:60]


problems = 0
for keep in [r for r in rows if r["Role"] == "KEEP"]:
    p = graphql(store, version, token, Q, {"h": keep["Handle"]})["data"]["productByHandle"]
    vs = p["variants"]["nodes"]
    media = p["media"]["nodes"]
    opts = "; ".join(f"{o['name']}({len(o['values'])})" for o in p["options"])
    nv = sum(1 for v in vs if v["v"])
    nd = sum(1 for v in vs if v["d"])
    failed = sum(1 for m in media if m["status"] == "FAILED")
    print(f"{keep['Group']} {keep['Handle'][:55]:55} {p['status']:6} vars={len(vs):3} vendor_sku={nv:3} "
          f"desc={nd:3} media={len(media)}{' FAILED=' + str(failed) if failed else ''} [{opts}]")
    print(f"      {p['title']} | SKUs {vs[0]['sku']} … {vs[-1]['sku']} | ${min(float(v['price']) for v in vs):.2f}"
          f"–${max(float(v['price']) for v in vs):.2f}")
    if nv != len(vs) or len(vs) < 2:
        problems += 1
        print("      !! variants without vendor_sku or still a single variant")
    for a in [r for r in rows if r["Group"] == keep["Group"] and r["Role"] == "ARCHIVE"]:
        code, loc = redirect(a["Handle"])
        ok = code in (301, 302) and loc.rstrip("/").endswith(keep["Handle"])
        problems += not ok
        print(f"      {'ok ' if ok else '!! '}{code} /products/{a['Handle'][:50]} -> {loc}")
print("problems", problems)
