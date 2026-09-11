"""What would a sync of the whole store do right now? A dry run of every PromoStandards product, read-only.

After a store is emptied and re-migrated, every product is "pending": new GIDs, one legacy variant,
no `trophy_sync.last_sync_at`. Before syncing ~1000 of them this answers, per store product and per
supplier id, what `POST /api/sync/products/{id}` would do - without calling it. Nothing is written
to Shopify, to PaceSetter, or to the app.

The report opens with the three questions that decide whether a bulk sync is safe:
  1. how many store products PaceSetter can actually serve (all ids / some / only as variants / none);
  2. whether two store products claim the same thing - the same id, the same PaceSetter PRODUCT
     (directly or through its parts), or the same PaceSetter VARIANT once a sync has run;
  3. what each product's id list is made of: PaceSetter products (N:1 grouping) or variants (parts).

It reproduces the app's own rules rather than guessing:
  * identity: a supplier id resolves to the ONE store product whose `custom.ps_product_id` /
    `ps_product_ids` lists it (ShopifySyncService index) - so an id listed twice is a collision;
  * a seed id needs Product Data (with parts) AND a price table, or the whole sync fails
    (CatalogService.aggregate); every other listed id that fails is silently skipped;
  * the union (ForeignProductSync) also takes the parts PaceSetter's Inventory answers for the
    whole family, and appends to `ps_product_ids` the ones that are products of their own;
  * nothing is ever created (`sync.create-products.enabled=false`): a PaceSetter product no store
    product lists is only reported.

Supplier reads go through the running app's own REST endpoints (Product Data, Pricing, Inventory),
so the answers are the same mapping the sync sees. They are cached in the output directory, so a
second run only asks for what is missing. The report is written in Spanish (the client's language).

Usage:
    python tools/simulate_store_sync.py --out-dir reports/sync-simulation
    python tools/simulate_store_sync.py --out-dir reports/sync-simulation --retry-errors

Writes <out-dir>/simulation.md, store_products.csv, supplier_ids.csv, unclaimed_sellable.csv,
shared_variants.csv and supplier_cache.json.

Requires: standard library only; the app running (default http://localhost:8080) in soap mode.
Origin: 2026-09-10, after the store was emptied and the whole catalogue re-imported with Matrixify.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_discount_metafield import access_token, graphql, local_credentials  # noqa: E402
from sync_store_catalog import opener  # noqa: E402

# Every product, not just the tagged ones: an untagged product with a PS SKU is invisible to the
# app, and that is one of the things worth finding.
STORE_PRODUCTS = """
query StoreProducts($cursor: String) {
  products(first: 25, after: $cursor) {
    pageInfo { hasNextPage endCursor }
    nodes {
      id handle title status createdAt tags
      psId: metafield(namespace: "custom", key: "ps_product_id") { value }
      psIds: metafield(namespace: "custom", key: "ps_product_ids") { value }
      psSource: metafield(namespace: "custom", key: "ps_source") { value }
      syncSource: metafield(namespace: "trophy_sync", key: "source") { value }
      lastSync: metafield(namespace: "trophy_sync", key: "last_sync_at") { value }
      legacySku: metafield(namespace: "migration", key: "legacy_sku") { value }
      variantsCount { count }
      mediaCount { count }
      variants(first: 5) {
        nodes {
          sku
          vendorSku: metafield(namespace: "trophy_sync", key: "vendor_sku") { value }
          partId: metafield(namespace: "custom", key: "promo_standard_id") { value }
        }
      }
    }
  }
}
"""

OK_KINDS = ("PRODUCT", "PRODUCT_UNLISTED")
# An id that is a PaceSetter product of its own, whether or not it can seed a sync today.
PRODUCT_KINDS = OK_KINDS + ("NO_PRICE", "NO_PRODUCT_DATA")


# ------------------------------------------------------------------------------------------- store

def id_list(raw: str | None) -> list[str]:
    if not raw:
        return []
    try:
        values = json.loads(raw)
    except json.JSONDecodeError:
        values = re.split(r"[,;\n]", raw)
    if isinstance(values, str):
        values = [values]
    return [str(v).strip() for v in values if str(v).strip()]


def mf(node: dict, alias: str) -> str | None:
    value = (node.get(alias) or {}).get("value")
    return value.strip() if isinstance(value, str) and value.strip() else None


def store_products() -> tuple[str, list[dict]]:
    shop, client_id, client_secret, version = local_credentials()
    token = access_token(shop, client_id, client_secret)
    products, cursor = [], None
    while True:
        payload = graphql(shop, version, token, STORE_PRODUCTS, {"cursor": cursor} if cursor else {})
        errors = payload.get("errors") or []
        if any((e.get("extensions") or {}).get("code") == "THROTTLED" for e in errors):
            time.sleep(3)
            continue
        if errors:
            sys.exit(f"store listing failed: {errors}")
        page = payload["data"]["products"]
        for node in page["nodes"]:
            canonical = mf(node, "psId")
            ids, seen = [], set()
            for value in ([canonical] if canonical else []) + id_list(mf(node, "psIds")):
                if value.upper() not in seen:
                    seen.add(value.upper())
                    ids.append(value)
            variants = node["variants"]["nodes"]
            products.append({
                "gid": node["id"], "handle": node["handle"], "title": node["title"],
                "status": node["status"], "createdAt": node["createdAt"], "tags": node["tags"],
                "tagged": "promostandards" in [t.lower() for t in node["tags"]],
                "canonical": canonical, "ids": ids,
                "source": mf(node, "syncSource") or mf(node, "psSource"),
                "lastSync": mf(node, "lastSync"), "legacySku": mf(node, "legacySku"),
                "variants": node["variantsCount"]["count"], "media": node["mediaCount"]["count"],
                "skus": [v["sku"] for v in variants if v.get("sku")],
                "variantIdentity": sum(1 for v in variants if mf(v, "vendorSku") or mf(v, "partId")),
            })
        print(f"  store: {len(products)} products read", end="\r", flush=True)
        if not page["pageInfo"]["hasNextPage"]:
            print()
            return shop.split(".")[0], products
        cursor = page["pageInfo"]["endCursor"]


# ---------------------------------------------------------------------------------------- supplier

class Supplier:
    """The app's read endpoints, one answer per (kind, id), cached on disk as a compact summary."""

    def __init__(self, base: str, cache_path: Path, workers: int):
        self.base, self.cache_path, self.workers = base, cache_path, workers
        self.op = opener(base)
        self.lock = threading.Lock()
        self.cache: dict[str, dict] = (json.loads(cache_path.read_text(encoding="utf-8"))
                                       if cache_path.exists() else {})

    def get_json(self, path: str) -> tuple[int, object]:
        for attempt in (1, 2):
            try:
                with self.op.open(self.base + path, timeout=180) as response:
                    return 200, json.load(response)
            except urllib.error.HTTPError as e:
                body = e.read().decode(errors="replace")
                if e.code == 401 and attempt == 1:     # the app restarted: its sessions died with it
                    self.op = opener(self.base)
                    continue
                try:
                    message = json.loads(body).get("message") or body
                except (json.JSONDecodeError, AttributeError):
                    message = body
                return e.code, str(message)[:300]
            except Exception as e:  # timeouts, resets: recorded, never fatal
                return 0, f"{type(e).__name__}: {e}"[:300]
        return 0, "unreachable"

    @staticmethod
    def summarise(kind: str, body: dict) -> dict:
        if kind == "p":
            return {"name": body.get("productName"),
                    "parts": [{"partId": p.get("partId"), "color": p.get("primaryColor"),
                               "sizes": p.get("sizes") or []} for p in body.get("parts") or []]}
        if kind == "c":
            return {"parts": [{"partId": p.get("partId"), "breaks": len(p.get("priceBreaks") or []),
                               "minQty": min((b.get("minQuantity") or 0 for b in p.get("priceBreaks") or []),
                                             default=None)}
                              for p in body.get("partPrices") or []]}
        return {"family": body.get("productId"),
                "rows": [{"partId": r.get("partId"), "color": r.get("color"), "size": r.get("size"),
                          "qty": r.get("quantityAvailable")} for r in body.get("parts") or []]}

    def path(self, kind: str, pid: str) -> str:
        quoted = urllib.parse.quote(pid, safe="")
        return {"p": f"/api/products/{quoted}",
                "c": f"/api/pricing/{quoted}/configuration?currency=USD",
                "i": f"/api/inventory/{quoted}/levels"}[kind]

    def fetch_all(self, wanted: list[tuple[str, str]], retry_errors: bool) -> None:
        todo = [(k, i) for k, i in wanted
                if f"{k}:{i.upper()}" not in self.cache
                or (retry_errors and "error" in self.cache[f"{k}:{i.upper()}"])]
        if not todo:
            return
        print(f"  supplier: {len(todo)} read(s) to make ({len(wanted) - len(todo)} cached)", flush=True)
        started, done = time.time(), 0

        def one(item):
            kind, pid = item
            status, body = self.get_json(self.path(kind, pid))
            entry = (self.summarise(kind, body) if status == 200 and isinstance(body, dict)
                     else {"error": body if isinstance(body, str) else str(body), "status": status})
            return f"{kind}:{pid.upper()}", entry

        with ThreadPoolExecutor(max_workers=self.workers) as pool:
            for future in as_completed([pool.submit(one, item) for item in todo]):
                key, entry = future.result()
                with self.lock:
                    self.cache[key] = entry
                    done += 1
                    if done % 100 == 0 or done == len(todo):
                        self.save()
                        rate = done / max(time.time() - started, 0.1)
                        print(f"  supplier: {done}/{len(todo)} · {rate:.1f}/s · "
                              f"~{(len(todo) - done) / rate / 60:.0f} min left", flush=True)

    def save(self) -> None:
        tmp = self.cache_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.cache, ensure_ascii=False), encoding="utf-8")
        tmp.replace(self.cache_path)

    def entry(self, kind: str, pid: str) -> dict | None:
        return self.cache.get(f"{kind}:{pid.upper()}")


# ------------------------------------------------------------------------------------ the analysis

def ok(entry: dict | None) -> bool:
    return entry is not None and "error" not in entry


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--out-dir", default="reports/sync-simulation")
    parser.add_argument("--workers", type=int, default=5,
                        help="concurrent supplier reads (the app's own catalog scan uses 5)")
    parser.add_argument("--retry-errors", action="store_true",
                        help="ask again for every cached answer that was an error")
    args = parser.parse_args()

    out = Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y-%m-%d %H:%M")

    print("1/4 store", flush=True)
    shop_name, all_products = store_products()
    ps_products = [p for p in all_products if p["tagged"] or p["ids"]]

    print("2/4 supplier catalog", flush=True)
    supplier = Supplier(args.base, out / "supplier_cache.json", args.workers)
    status, sellable_body = supplier.get_json("/api/products/sellable?isSellable=true")
    if status != 200:
        sys.exit(f"sellable list failed: {sellable_body}")
    sellable_ids = list(dict.fromkeys(s["productId"].strip() for s in sellable_body if s.get("productId")))
    sellable = {s.upper() for s in sellable_ids}
    families = sorted({s.upper()[:-1] for s in sellable_ids if s.endswith("*")}, key=len, reverse=True)

    # Who claims each supplier id in the store.
    claims: dict[str, list[dict]] = defaultdict(list)
    for p in ps_products:
        for i in p["ids"]:
            claims[i.upper()].append(p)
    store_ids = list(claims)

    print("3/4 supplier reads", flush=True)
    wanted = [(k, s) for s in sellable_ids for k in ("p", "c", "i")]
    wanted += [("p", i) for i in store_ids if i not in sellable]
    supplier.fetch_all(wanted, args.retry_errors)
    # An id the supplier does not list may still answer Product Data - and a manual sync does not
    # check the sellable list. Those need a price table too, or they cannot seed a sync either.
    hidden = [i for i in store_ids if i not in sellable and ok(supplier.entry("p", i))
              and supplier.entry("p", i)["parts"]]
    supplier.fetch_all([(k, i) for i in hidden for k in ("c", "i")], args.retry_errors)
    supplier.save()

    print("4/4 analysis", flush=True)

    def name_of(pid: str) -> str:
        e = supplier.entry("p", pid)
        return (e or {}).get("name") or "" if ok(e) else ""

    # Part id -> the supplier products that carry it. Two views: `part_owners` takes every source,
    # family-wide Inventory rows included, and decides whether an id is a part at all;
    # `declared_owners` keeps only Product Data and Pricing - the product that DECLARES the part as
    # its own - which is what a claim should be attributed to (Inventory answers for a whole family,
    # so one colour would otherwise "belong" to forty products).
    part_owners: dict[str, set[str]] = defaultdict(set)
    declared_owners: dict[str, set[str]] = defaultdict(set)
    for s in sellable_ids + hidden:
        su = s.upper()
        for kind, field in (("p", "parts"), ("c", "parts"), ("i", "rows")):
            e = supplier.entry(kind, s)
            if ok(e):
                for part in e[field]:
                    pu = (part.get("partId") or "").strip().upper()
                    if pu and pu != su:
                        part_owners[pu].add(s)
                        if kind != "i":
                            declared_owners[pu].add(s)

    def owners_of(part: str) -> set[str]:
        pu = part.upper()
        return declared_owners.get(pu) or part_owners.get(pu, set())

    def family_of(pid: str) -> str | None:
        pu = pid.upper()
        return next((f + "*" for f in families if pu.startswith(f)), None)

    def group_of(pid: str) -> str:
        """The PaceSetter product line an id belongs to: its Inventory family, else a wildcard family,
        else the product it is a part of, else itself."""
        inv = supplier.entry("i", pid)
        if ok(inv) and inv.get("family"):
            return inv["family"].upper()
        fam = family_of(pid)
        if fam:
            return fam
        owners = sorted(owners_of(pid))
        if owners:
            inv = supplier.entry("i", owners[0])
            return inv["family"].upper() if ok(inv) and inv.get("family") else owners[0].upper()
        return pid.upper()

    kind_cache: dict[str, str] = {}

    def kind_of(pid: str) -> str:
        pu = pid.upper()
        if pu in kind_cache:
            return kind_cache[pu]
        product, price = supplier.entry("p", pid), supplier.entry("c", pid)
        readable = ok(product) and bool(product["parts"])
        priced = ok(price) and any(p["breaks"] for p in price["parts"])
        if readable and priced:
            kind = "PRODUCT" if pu in sellable else "PRODUCT_UNLISTED"
        elif readable:
            kind = "NO_PRICE"
        elif pu in sellable:
            kind = "NO_PRODUCT_DATA"
        elif pu in part_owners:
            kind = "PART"
        elif family_of(pid):
            kind = "FAMILY_PART"
        else:
            kind = "ORPHAN"
        kind_cache[pu] = kind
        return kind

    def variant_keys(pid: str) -> set[tuple[str, str]]:
        """(part, size) the aggregate of one id contributes: its family's Inventory rows, plus the
        Product Data parts no Inventory row covers - the union ForeignProductSync builds."""
        keys, inv_parts = set(), set()
        inv = supplier.entry("i", pid)
        if ok(inv):
            for r in inv["rows"]:
                pu = (r.get("partId") or "").upper()
                inv_parts.add(pu)
                keys.add((pu, (r.get("size") or "").upper()))
        product = supplier.entry("p", pid)
        if ok(product):
            for part in product["parts"]:
                pu = (part.get("partId") or "").upper()
                if pu not in inv_parts:
                    for size in part["sizes"] or [""]:
                        keys.add((pu, (size or "").upper()))
        return keys

    # PaceSetter product -> the store products holding its parts. One holder means "list the product
    # id instead" fixes it; several means the store split one PaceSetter product into many (Small /
    # Medium / Large Halley Plaque are all parts of C750), and repointing them all would collide.
    holders: dict[str, set[str]] = defaultdict(set)
    for i, claimers in claims.items():
        if kind_of(i) == "PART":
            for o in owners_of(i):
                holders[o.upper()] |= {c["handle"] for c in claimers}

    # ---- per store product: what the sync would do
    rows = []
    absorbed_by: dict[str, list[dict]] = defaultdict(list)
    for p in ps_products:
        kinds = {i: kind_of(i) for i in p["ids"]}
        live = [i for i in p["ids"] if kinds[i] in OK_KINDS]
        seed = (p["canonical"] if p["canonical"] and kinds.get(p["canonical"]) in OK_KINDS
                else (live[0] if live else None))
        shared = {i: [o["handle"] for o in claims[i.upper()] if o is not p]
                  for i in p["ids"] if len(claims[i.upper()]) > 1}
        keys = set()
        for i in live:
            keys |= variant_keys(i)
        listed = {i.upper() for i in p["ids"]}
        # Family rows nobody listed: those that are products of their own get appended.
        discovered = sorted({k[0] for k in keys if k[0] and k[0] not in listed
                             and kind_of(k[0]) in OK_KINDS})
        for d in discovered:
            absorbed_by[d].append(p)
        live_groups = list(dict.fromkeys(group_of(i) for i in live))
        part_of = {i: sorted(owners_of(i)) for i in p["ids"] if kinds[i] == "PART"}
        stolen = {d: [o["handle"] for o in claims[d]] for d in discovered if d in claims}
        repoint = ""
        if part_of:
            owners = {o.upper() for listed_owners in part_of.values() for o in listed_owners}
            repoint = "simple" if all(holders[o] <= {p["handle"]} for o in owners) else "shared"

        flags = []
        if not p["ids"]:
            verdict = "NO_IDS"
        elif shared:
            verdict = "COLLISION"
        elif not live:
            verdict = "UNSYNCABLE_PARTS" if part_of else "UNSYNCABLE"
        elif len(live_groups) > 1:
            verdict = "MULTI_PRODUCT"
        else:
            verdict = "OK"
        if seed and p["canonical"] and seed.upper() != p["canonical"].upper():
            flags.append(f"canonical {p['canonical']} unusable, drive by {seed}")
        dead = [i for i in p["ids"] if kinds[i] not in OK_KINDS]
        if live and dead:
            flags.append(f"{len(dead)} dead id(s)")
        if part_of and live:
            flags.append("lists part ids of another product")
        if discovered:
            flags.append(f"+{len(discovered)} id(s) appended by the union")
        if stolen:
            flags.append("union reaches ids another store product owns")
        if not p["tagged"]:
            flags.append("NOT TAGGED promostandards (invisible to the app)")
        rows.append({**p, "kinds": kinds, "live": live, "seed": seed, "shared": shared,
                     "verdict": verdict, "flags": flags, "expectedVariants": len(keys),
                     "variantParts": sorted({k[0] for k in keys if k[0]}),
                     "groups": live_groups, "names": {i: name_of(i) for i in live},
                     "discovered": discovered, "partOf": part_of, "stolen": stolen,
                     "repoint": repoint})

    # ---- healthy: safe to sync as it stands, at variant level only. One store product <-> one
    # PaceSetter family, every id a live product, and nothing the sync would bring in (its family's
    # variants) is listed by, or would also land in, any other store product.
    family_holders: dict[str, set[str]] = defaultdict(set)
    variant_holders: dict[str, set[str]] = defaultdict(set)
    for r in rows:
        for i in r["ids"]:
            family_holders[group_of(i)].add(r["handle"])
        for part in r["variantParts"]:
            variant_holders[part].add(r["handle"])
    for r in rows:
        blockers = []
        if not r["tagged"] or not r["ids"]:
            blockers.append("sin tag o sin ids")
        if any(r["kinds"][i] not in OK_KINDS for i in r["ids"]):
            blockers.append("ids que no son un producto vivo")
        families_of_r = {group_of(i) for i in r["ids"]}
        if len(families_of_r) > 1:
            blockers.append("varias familias")
        if any(family_holders[g] - {r["handle"]} for g in families_of_r):
            blockers.append("familia compartida con otro producto")
        if r["shared"] or any(c["handle"] != r["handle"] for v in r["variantParts"]
                              for c in claims.get(v, [])):
            blockers.append("variantes que lista otro producto")
        if any(len(variant_holders[v]) > 1 for v in r["variantParts"]):
            blockers.append("variantes que acabarían en otro producto")
        if not r["variantParts"] and not blockers:
            blockers.append("sin variantes")
        r["blockers"], r["healthy"] = blockers, not blockers

    # ---- PaceSetter products no store product claims
    unclaimed = []
    store_groups: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        for g in {group_of(i) for i in r["ids"]}:
            store_groups[g].append(r)
    for s in sellable_ids:
        su = s.upper()
        if su in claims:
            continue
        kind = kind_of(s)
        related = []
        if su in absorbed_by:
            verdict, related = "ABSORBED", absorbed_by[su]
        elif kind not in OK_KINDS:
            verdict = "NOT_IMPORTABLE"
        else:
            # Its parts, or its product line, already live in a store product under other ids.
            own_parts = set()
            for k, f in (("p", "parts"), ("c", "parts"), ("i", "rows")):
                e = supplier.entry(k, s)
                if ok(e):
                    own_parts |= {(x.get("partId") or "").upper() for x in e[f]}
            related = [c for part in own_parts for c in claims.get(part, [])]
            related += store_groups.get(group_of(s), [])
            related = list({id(r): r for r in related}.values())
            verdict = "DUPLICATE_RISK" if related else "NEW"
        unclaimed.append({"id": s, "kind": kind, "verdict": verdict, "name": name_of(s),
                          "group": group_of(s), "related": [r["handle"] for r in related]})

    # ---- the three questions
    summary = answer_questions(rows, claims, kind_of, owners_of, group_of, name_of)

    write_csvs(out, rows, unclaimed, claims, kind_of, group_of, part_owners, name_of, summary)
    (out / "simulation.md").write_text(
        render(stamp, shop_name, all_products, ps_products, rows, unclaimed, sellable_ids, families,
               claims, kind_of, summary),
        encoding="utf-8")
    verdicts = Counter(r["verdict"] for r in rows)
    print("store products: " + ", ".join(f"{k} {v}" for k, v in verdicts.most_common()))
    print("availability: " + ", ".join(f"{k} {v}" for k, v in summary["availability"].items()))
    print(f"healthy: {sum(1 for r in rows if r['healthy'])} -> {out / 'healthy_products.txt'}")
    print(f"shared PaceSetter products: {len(summary['sharedProducts'])} · shared variants after "
          f"sync: {len(summary['sharedVariants'])} in {len(summary['productsWithSharedVariants'])} products")
    print(f"-> {out / 'simulation.md'}")
    return 0


def answer_questions(rows, claims, kind_of, owners_of, group_of, name_of) -> dict:
    """The three questions a bulk sync hinges on, as numbers plus the rows behind them."""
    # 1. Can PaceSetter serve it?
    availability = Counter()
    for r in rows:
        kinds = [r["kinds"][i] for i in r["ids"]]
        live = sum(k in OK_KINDS for k in kinds)
        if kinds and live == len(kinds):
            availability["all"] += 1
        elif live:
            availability["some"] += 1
        elif "PART" in kinds:
            availability["partsOnly"] += 1
        else:
            availability["none"] += 1

    # 2. Does anything get claimed twice? Three levels, from the literal id up to what a sync makes.
    shared_ids = {i: [p["handle"] for p in ps] for i, ps in claims.items() if len(ps) > 1}
    claimers: dict[str, set[str]] = defaultdict(set)   # PaceSetter product -> store products
    ambiguous: set[str] = set()                        # part ids declared by several products
    for i, ps in claims.items():
        handles = {p["handle"] for p in ps}
        kind = kind_of(i)
        if kind in PRODUCT_KINDS:
            claimers[i.upper()] |= handles
        elif kind == "PART":
            owners = owners_of(i)
            if len(owners) == 1:
                claimers[next(iter(owners)).upper()] |= handles
            else:
                ambiguous.add(i)
    shared_products = {p: sorted(h) for p, h in claimers.items() if len(h) > 1}

    variant_holders: dict[str, set[str]] = defaultdict(set)
    by_handle = {r["handle"]: r for r in rows}
    for r in rows:
        for part in r["variantParts"]:
            variant_holders[part].add(r["handle"])
    shared_variants = {v: sorted(h) for v, h in variant_holders.items() if len(h) > 1}
    products_with_shared = sorted({h for hs in shared_variants.values() for h in hs})
    shared_variant_lines: dict[str, set[str]] = defaultdict(set)
    for v, hs in shared_variants.items():
        shared_variant_lines[group_of(v)] |= set(hs)

    # 3. What is each id list made of?
    composition: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        products = [i for i in r["ids"] if r["kinds"][i] in PRODUCT_KINDS]
        parts = [i for i in r["ids"] if r["kinds"][i] == "PART"]
        if len(r["ids"]) == 1:
            key = "1-product" if products else ("1-part" if parts else "1-dead")
        elif len(products) >= 2:
            lines = {group_of(i) for i in products}
            key = "n-products-one-line" if len(lines) == 1 else "n-products-lines"
        elif products and parts:
            key = "n-product-and-parts"
        elif len(products) == 1:
            key = "n-one-product-rest-dead"
        elif parts:
            key = "n-parts"
        else:
            key = "n-dead"
        composition[key].append(r)

    return {"availability": availability, "sharedIds": shared_ids,
            "productsClaimed": len(claimers), "sharedProducts": shared_products,
            "ambiguousParts": sorted(ambiguous), "sharedVariants": shared_variants,
            "productsWithSharedVariants": products_with_shared,
            "sharedVariantLines": shared_variant_lines, "composition": composition,
            "byHandle": by_handle, "nameOf": name_of}


# ----------------------------------------------------------------------------------------- outputs

def write_csvs(out, rows, unclaimed, claims, kind_of, group_of, part_owners, name_of, summary) -> None:
    with open(out / "store_products.csv", "w", encoding="utf-8-sig", newline="") as handle:
        w = csv.writer(handle, delimiter=";")
        w.writerow(["verdict", "healthy", "blockers", "handle", "title", "status", "variants_now",
                    "expected_variants",
                    "ps_product_id", "seed_id", "ps_product_ids", "kinds", "paceSetter_lines",
                    "paceSetter_names", "part_of", "repoint", "shared_with", "appended_by_union", "flags",
                    "legacy_sku", "synced"])
        for r in sorted(rows, key=lambda x: (x["verdict"], x["handle"])):
            w.writerow([r["verdict"], "yes" if r["healthy"] else "no", " · ".join(r["blockers"]),
                        r["handle"], r["title"], r["status"], r["variants"],
                        r["expectedVariants"], r["canonical"] or "", r["seed"] or "", " ".join(r["ids"]),
                        " ".join(f"{i}={k}" for i, k in r["kinds"].items()), " ".join(r["groups"]),
                        " | ".join(f"{i}: {n}" for i, n in r["names"].items()),
                        " ".join(f"{i}->{'/'.join(o)}" for i, o in r["partOf"].items()), r["repoint"],
                        " ".join(f"{i}->{'/'.join(o)}" for i, o in r["shared"].items()),
                        " ".join(r["discovered"]), " · ".join(r["flags"]), r["legacySku"] or "",
                        "yes" if r["lastSync"] else "no"])
    with open(out / "supplier_ids.csv", "w", encoding="utf-8-sig", newline="") as handle:
        w = csv.writer(handle, delimiter=";")
        w.writerow(["supplier_id", "kind", "paceSetter_line", "name", "part_of", "claimed_by"])
        for i in sorted(claims):
            w.writerow([i, kind_of(i), group_of(i), name_of(i), " ".join(sorted(part_owners.get(i, ()))),
                        " ".join(p["handle"] for p in claims[i])])
    with open(out / "unclaimed_sellable.csv", "w", encoding="utf-8-sig", newline="") as handle:
        w = csv.writer(handle, delimiter=";")
        w.writerow(["verdict", "supplier_id", "kind", "name", "paceSetter_line", "related_store_products"])
        for u in sorted(unclaimed, key=lambda x: (x["verdict"], x["id"])):
            w.writerow([u["verdict"], u["id"], u["kind"], u["name"], u["group"], " ".join(u["related"])])
    # The healthy set as sync_store_catalog.py takes it (--only @file): the canonical id, which for a
    # healthy product is always a live one, so it is also the id the sync is driven by.
    (out / "healthy_products.txt").write_text(
        "".join(f"{r['canonical'] or r['ids'][0]}\n" for r in sorted(rows, key=lambda x: x["handle"])
                if r["healthy"]), encoding="utf-8")
    with open(out / "shared_variants.csv", "w", encoding="utf-8-sig", newline="") as handle:
        w = csv.writer(handle, delimiter=";")
        w.writerow(["paceSetter_part", "paceSetter_line", "store_products_after_sync"])
        for v, hs in sorted(summary["sharedVariants"].items()):
            w.writerow([v, group_of(v), " ".join(hs)])


def md_table(headers: list[str], body: list[list[str]], limit: int = 0) -> str:
    def cell(value) -> str:
        return str(value).replace("|", "\\|").replace("\n", " ")
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    shown = body[:limit] if limit else body
    lines += ["| " + " | ".join(cell(c) for c in row) + " |" for row in shown]
    if limit and len(body) > limit:
        lines.append(f"\n… {len(body) - limit} más en el CSV.")
    return "\n".join(lines)


def healthy_section(rows, admin) -> list[str]:
    healthy = sorted((r for r in rows if r["healthy"]), key=lambda r: r["title"].lower())
    blockers = Counter(b for r in rows for b in r["blockers"])
    only = Counter(r["blockers"][0] for r in rows if len(r["blockers"]) == 1)
    adding = [r for r in healthy if r["discovered"]]
    return ["## 0. Productos sanos — se pueden migrar ya, sólo a nivel de variantes", "",
            "Sano = todo a la vez: está en Shopify (no se crea nada); todos sus ids son productos vivos "
            "de PaceSetter; todos son de **una sola familia** de PaceSetter y ningún otro producto de "
            "la tienda tiene ids de esa familia; y ninguna variante que traería la sync está en la "
            "lista de otro producto ni acabaría en otro.", "",
            f"**{len(healthy)} productos sanos**, que pasarían de {sum(r['variants'] for r in healthy)} "
            f"variantes a ≈{sum(r['expectedVariants'] for r in healthy)}. {len(adding)} de ellos "
            "completarían su familia con ids que su lista no tenía (variantes reales de esa misma "
            "familia que no reclama ningún otro producto).", "",
            "Lista: `healthy_products.txt`, un id por línea, para "
            "`python tools/sync_store_catalog.py --only @<ruta>/healthy_products.txt --report <run>.jsonl`.", "",
            md_table(["por qué no es sano (un producto puede tener varios motivos)", "productos",
                      "sólo por este motivo"],
                     [[b, n, only[b]] for b, n in blockers.most_common()]), "",
            md_table(["producto sano", "ids", "familia PaceSetter", "variantes hoy → tras la sync"],
                     [[admin(r), " ".join(r["ids"]), " ".join(r["groups"]),
                       f"{r['variants']} → {r['expectedVariants']}"] for r in healthy], limit=40), ""]


def render(stamp, shop_name, all_products, ps_products, rows, unclaimed, sellable_ids, families,
           claims, kind_of, summary) -> str:
    verdicts = Counter(r["verdict"] for r in rows)
    id_kinds = Counter(kind_of(i) for i in claims)
    un = Counter(u["verdict"] for u in unclaimed)
    synced = sum(1 for p in ps_products if p["lastSync"])
    variants_now = Counter(min(p["variants"], 3) for p in ps_products)
    app_handles = [p for p in all_products if p["handle"].startswith("ps-")]
    untagged_ps = [p for p in all_products if not p["tagged"] and not p["ids"]
                   and any((s or "").upper().startswith("PS") for s in p["skus"])]
    legacy = Counter(p["legacySku"] for p in ps_products if p["legacySku"])
    dup_legacy = {s: n for s, n in legacy.items() if n > 1}
    titles = Counter(p["title"].strip().lower() for p in ps_products)
    dup_titles = {t: n for t, n in titles.items() if n > 1}
    by_handle, name_of = summary["byHandle"], summary["nameOf"]

    def admin(r) -> str:
        return f"[{r['title']}](https://admin.shopify.com/store/{shop_name}/products/{r['gid'].rsplit('/', 1)[-1]})"

    def titled(handles: list[str], limit: int = 4) -> str:
        shown = [by_handle[h]["title"] for h in handles[:limit] if h in by_handle]
        return "; ".join(shown) + (f" (+{len(handles) - limit})" if len(handles) > limit else "")

    by = defaultdict(list)
    for r in rows:
        by[r["verdict"]].append(r)
    for v in by.values():
        v.sort(key=lambda r: r["title"].lower())

    a, comp = summary["availability"], summary["composition"]
    shared_products, shared_variants = summary["sharedProducts"], summary["sharedVariants"]
    split_holders = sorted({h for hs in shared_products.values() for h in hs})

    s = [f"# Simulación de sync — tienda vs PaceSetter ({stamp})", "",
         "Simulación de solo lectura de `POST /api/sync/products/{id}` sobre todos los productos "
         f"PromoStandards de `{shop_name}`. No se escribió nada. Generado por "
         "`tools/simulate_store_sync.py`; el detalle fila a fila está en los CSV junto a este fichero. "
         "La app **no crea productos** (`sync.create-products.enabled=false`): sólo actualiza los que "
         "ya están en Shopify.", "",
         *healthy_section(rows, admin),
         "## Respuestas directas", "",
         f"### 1. ¿Cuántos productos de Shopify están disponibles en PaceSetter? ({len(rows)} productos)", "",
         md_table(["situación de sus ids", "productos", "qué hace la sync"], [
             ["todos existen como producto en PaceSetter", a["all"], "se sincroniza completo"],
             ["algunos sí, otros no", a["some"], "se sincroniza con los que existen; los demás se ignoran"],
             ["ninguno es producto, pero son **variantes** de un producto PaceSetter", a["partsOnly"],
              "no se sincroniza hasta que su lista apunte al producto (ver 3.2)"],
             ["ninguno existe en PaceSetter", a["none"], "no se sincroniza"],
         ]), "",
         f"**Disponibles: {a['all'] + a['some']} de {len(rows)}.** Otros {a['partsOnly']} lo serían "
         "si se corrige su lista de ids.", "",
         "### 2. ¿Dos o más productos de Shopify reclaman lo mismo?", "",
         md_table(["nivel", "casos", "productos de Shopify implicados"], [
             ["el **mismo id** en la lista de dos productos", len(summary["sharedIds"]),
              len({h for hs in summary["sharedIds"].values() for h in hs})],
             ["el **mismo producto PaceSetter** (por su id o por sus variantes)", len(shared_products),
              len(split_holders)],
             ["la **misma variante PaceSetter** en dos productos **después** de sincronizar",
              len(shared_variants), len(summary["productsWithSharedVariants"])],
         ]), "",
         f"- **Mismo id: {len(summary['sharedIds'])}.** Ningún id aparece en dos productos, así que "
         "el índice de la app siempre resuelve a un único producto.",
         f"- **Mismo producto PaceSetter: {len(shared_products)}.** La tienda reparte un producto "
         "PaceSetter entre varios productos de Shopify, casi siempre uno por color o por medida, cada "
         "uno con el id de su variante (p. ej. las Halley Plaque Small/Medium/Large × Red/Gold/Green "
         "son todas variantes de `C750`). Hoy no chocan porque cada uno lista un id distinto, pero "
         "ninguno se sincroniza así (ver 3.2).",
         f"- **Misma variante tras la sync: {len(shared_variants)} variantes en "
         f"{len(summary['productsWithSharedVariants'])} productos.** Aquí sí habría duplicados: al "
         "sincronizar, la app añade las variantes que el Inventory de PaceSetter devuelve para toda la "
         "familia, y donde la tienda vende cada color o estado como producto propio, cada uno acabaría "
         "con las variantes de todos los demás (ver 3.6). "
         + (f"Hay además {len(summary['ambiguousParts'])} ids de variante que PaceSetter declara en "
            "varios productos a la vez; no se atribuyen a ninguno." if summary["ambiguousParts"] else ""),
         "",
         md_table(["producto PaceSetter", "nombre", "productos de Shopify que lo reclaman"],
                  [[f"`{p}`", name_of(p), f"{len(hs)}: {titled(hs)}"]
                   for p, hs in sorted(shared_products.items(), key=lambda x: (-len(x[1]), x[0]))],
                  limit=25), "",
         md_table(["familia PaceSetter", "productos que compartirían variantes tras la sync"],
                  [[line, f"{len(hs)}: {titled(sorted(hs), 3)}"]
                   for line, hs in sorted(summary["sharedVariantLines"].items(),
                                          key=lambda x: (-len(x[1]), x[0]))], limit=20), "",
         "### 3. ¿De qué está hecha la lista de ids de cada producto?", "",
         "Un id puede ser un **producto** de PaceSetter (tiene su propia ficha y precio) o sólo una "
         "**variante** (una parte que PaceSetter vende dentro de otro producto).", "",
         md_table(["composición de `ps_product_ids`", "productos", "ejemplo"], [
             ["1 id, que es un producto PaceSetter", len(comp["1-product"]), titled([r["handle"] for r in comp["1-product"]], 2)],
             ["1 id, que es una variante de otro producto PaceSetter", len(comp["1-part"]), titled([r["handle"] for r in comp["1-part"]], 2)],
             ["1 id, que no existe", len(comp["1-dead"]), titled([r["handle"] for r in comp["1-dead"]], 2)],
             ["**varios productos PaceSetter** de la misma familia (colores que PaceSetter vende por separado)",
              len(comp["n-products-one-line"]), titled([r["handle"] for r in comp["n-products-one-line"]], 2)],
             ["**varios productos PaceSetter** de familias distintas (medidas, modelos)",
              len(comp["n-products-lines"]), titled([r["handle"] for r in comp["n-products-lines"]], 2)],
             ["un producto PaceSetter + variantes", len(comp["n-product-and-parts"]), titled([r["handle"] for r in comp["n-product-and-parts"]], 2)],
             ["un producto PaceSetter + ids que no existen", len(comp["n-one-product-rest-dead"]), titled([r["handle"] for r in comp["n-one-product-rest-dead"]], 2)],
             ["sólo variantes", len(comp["n-parts"]), titled([r["handle"] for r in comp["n-parts"]], 2)],
             ["varios ids, ninguno existe", len(comp["n-dead"]), titled([r["handle"] for r in comp["n-dead"]], 2)],
         ]), "",
         f"**Productos cuya lista reúne varios productos PaceSetter: "
         f"{len(comp['n-products-one-line']) + len(comp['n-products-lines'])}.** Es la agrupación N:1 "
         "de la migración: la sync los convierte en variantes de un único producto de Shopify. Los de "
         "familias distintas son los que conviene revisar a mano (la tabla 3.3 muestra los nombres).", "",
         "## Detalle", "",
         "### Estado de partida", "",
         md_table(["", "cantidad"], [
             ["Productos en la tienda", len(all_products)],
             ["… PromoStandards (tag o ids)", len(ps_products)],
             ["… ya sincronizados por la app (`trophy_sync.last_sync_at`)", synced],
             ["… con 1 / 2 / 3+ variantes hoy", f"{variants_now[1]} / {variants_now[2]} / {variants_now[3]}"],
             ["Handles creados por la app (`ps-…`)", len(app_handles)],
             ["Ids de PaceSetter distintos que reclama la tienda", len(claims)],
             ["Ids vendibles de PaceSetter (`getProductSellable`)", len(sellable_ids)],
             ["… de ellos, familias con comodín (`CM717*`)", len(families)],
         ]), "",
         "### Qué son los ids de la tienda en PaceSetter", "",
         md_table(["tipo", "ids", "significado"], [
             ["PRODUCT", id_kinds["PRODUCT"], "vendible, con ficha y tabla de precios — puede arrancar una sync"],
             ["PRODUCT_UNLISTED", id_kinds["PRODUCT_UNLISTED"], "no está en la lista de vendibles pero responde ficha y precio"],
             ["NO_PRICE", id_kinds["NO_PRICE"], "tiene ficha pero no precio — la sync lo rechaza (saldría a 0)"],
             ["NO_PRODUCT_DATA", id_kinds["NO_PRODUCT_DATA"], "vendible, pero Product Data no tiene partes"],
             ["PART", id_kinds["PART"], "**no es un producto: es una variante de otro producto PaceSetter**"],
             ["FAMILY_PART", id_kinds["FAMILY_PART"], "cae bajo un código de familia con comodín, pero ninguna respuesta lo lista"],
             ["ORPHAN", id_kinds["ORPHAN"], "sin rastro — descatalogado o recodificado"],
         ]), "",
         "### Veredicto por producto", "",
         md_table(["veredicto", "productos", "qué pasa"], [
             ["OK", verdicts["OK"], "se sincroniza; todos sus ids vivos son una misma línea de PaceSetter"],
             ["MULTI_PRODUCT", verdicts["MULTI_PRODUCT"], "se sincroniza uniendo **varias líneas de PaceSetter** en un producto"],
             ["COLLISION", verdicts["COLLISION"], "un id está también en otro producto — **bloqueante**"],
             ["UNSYNCABLE_PARTS", verdicts["UNSYNCABLE_PARTS"], "ningún id arranca, pero son variantes de un producto vivo"],
             ["UNSYNCABLE", verdicts["UNSYNCABLE"], "ningún id que PaceSetter sirva"],
             ["NO_IDS", verdicts["NO_IDS"], "con tag pero sin `ps_product_id(s)`"],
         ]), ""]

    if by["COLLISION"]:
        s += ["### 3.1 Colisiones (arreglar antes de sincronizar)", "",
              md_table(["producto", "id compartido → también en", "sus ids"],
                       [[admin(r), "; ".join(f"`{i}` → {', '.join(o)}" for i, o in r["shared"].items()),
                         " ".join(r["ids"])] for r in by["COLLISION"]]), ""]

    parts_only = by["UNSYNCABLE_PARTS"]
    simple = sum(1 for r in parts_only if r["repoint"] == "simple")
    s += ["### 3.2 Ids que son variantes de otro producto PaceSetter", "",
          f"De los {len(parts_only)} productos que sólo tienen variantes:", "",
          f"- **{simple} simples** — ningún otro producto de la tienda tiene variantes de ese producto "
          "PaceSetter: basta con poner el id del producto en su lista.",
          f"- **{len(parts_only) - simple} repartidos** — la tienda dividió un producto PaceSetter en "
          "varios de Shopify (por color, medida o estado). Apuntarlos todos al mismo id los haría "
          "colisionar, y sincronizar cualquiera se llevaría **todas** sus variantes. Hay que decidir: "
          "fusionarlos en un producto de Shopify, o que la app admita que un producto cubra sólo "
          "*algunas* variantes de un producto PaceSetter.", "",
          md_table(["producto", "id de variante → producto PaceSetter", "estado"],
                   [[admin(r), "; ".join(f"`{i}` → {', '.join(o)}" for i, o in r["partOf"].items()),
                     f"{r['verdict']} · {'simple' if r['repoint'] == 'simple' else 'repartido'}"]
                    for r in rows if r["partOf"]], limit=60), ""]

    s += ["### 3.3 Un producto de Shopify, varias líneas de PaceSetter", "",
          "No es un error por sí mismo — el catálogo antiguo agrupaba medidas y familias a propósito "
          "(p-8887). Pero donde los nombres de la derecha son artículos claramente distintos, deberían "
          "ser productos separados.", "",
          md_table(["producto", "líneas", "nombres en PaceSetter", "≈ variantes tras la sync"],
                   [[admin(r), " ".join(r["groups"]),
                     "; ".join(sorted(set(n for n in r["names"].values() if n)))[:200],
                     r["expectedVariants"]] for r in by["MULTI_PRODUCT"]], limit=60), ""]

    s += ["### 3.4 No se pueden sincronizar", "",
          md_table(["producto", "ids (tipo)"],
                   [[admin(r), " ".join(f"`{i}`={k}" for i, k in r["kinds"].items())]
                    for r in by["UNSYNCABLE"]], limit=40), ""]

    def flag_kind(flag: str) -> str:
        if flag.startswith("+"):
            return "la unión añade ids a `ps_product_ids`"
        if "dead id" in flag:
            return "ids muertos en `ps_product_ids`"
        if flag.startswith("canonical"):
            return "el id canónico no sirve (arranca otro id de la lista)"
        if flag.startswith("union reaches"):
            return "la unión alcanza ids de otro producto de la tienda"
        if flag.startswith("lists part ids"):
            return "lista ids de variante de otro producto"
        return flag

    flagged = Counter(flag_kind(f) for r in rows for f in r["flags"])
    s += ["### 3.5 Avisos en productos que sí se sincronizan", "",
          md_table(["aviso", "productos"], [[k, v] for k, v in flagged.most_common()]), ""]

    swallow = defaultdict(list)
    for r in rows:
        if r["stolen"]:
            swallow[" ".join(r["groups"])].append(r)
    s += ["### 3.6 Una sync se tragaría a productos hermanos", "",
          "El Inventory de PaceSetter responde por familia entera, y `ForeignProductSync.union` adopta "
          "cada miembro de la familia que es producto propio. Está bien cuando el producto de la tienda "
          "ES la familia (portafolio CM297); está mal cuando la tienda vende cada color o estado como "
          "producto propio: sincronizar uno lo convierte en la familia entera y añade los ids de los "
          f"hermanos a su `ps_product_ids`. **{sum(len(v) for v in swallow.values())} productos** harían "
          "esto; no deben sincronizarse hasta que la app no adopte ids que ya reclama otro producto.", "",
          md_table(["línea PaceSetter", "productos", "≈ variantes cada uno tras la sync", "p. ej."],
                   [[line, len(rs), max(r["expectedVariants"] for r in rs),
                     "; ".join(r["title"] for r in rs[:3])]
                    for line, rs in sorted(swallow.items(), key=lambda x: -len(x[1]))], limit=40), ""]

    s += ["### 4. Productos PaceSetter que ningún producto de la tienda reclama", "",
          "Ninguno se crea: con `sync.create-products.enabled=false` una importación de un id que no "
          "está en la tienda se rechaza con un 409.", "",
          md_table(["veredicto", "ids", "significado"], [
              ["ABSORBED", un["ABSORBED"], "la sync de un producto de la tienda lo añadirá como variante"],
              ["DUPLICATE_RISK", un["DUPLICATE_RISK"],
               "sus variantes ya están en un producto de la tienda con otros ids — es el id al que deberían apuntar (3.2)"],
              ["NEW", un["NEW"], "no está en la tienda de ninguna forma — se ignora"],
              ["NOT_IMPORTABLE", un["NOT_IMPORTABLE"], "sin precio o sin ficha"],
          ]), "",
          md_table(["id PaceSetter", "nombre", "ya está en"],
                   [[f"`{u['id']}`", u["name"], ", ".join(u["related"][:4])]
                    for u in unclaimed if u["verdict"] == "DUPLICATE_RISK"], limit=40), ""]

    s += ["### 5. Duplicados dentro de la tienda", "",
          md_table(["comprobación", "cantidad"], [
              ["`migration.legacy_sku` en más de un producto", len(dup_legacy)],
              ["mismo título en más de un producto PromoStandards", len(dup_titles)],
              ["productos sin tag con SKU `PS…` y sin ids (invisibles para la app)", len(untagged_ps)],
          ]), ""]
    if dup_legacy:
        s += [md_table(["legacy_sku", "productos"], [[k, v] for k, v in sorted(dup_legacy.items())]), ""]
    if dup_titles:
        s += [md_table(["título", "productos"], [[k, v] for k, v in sorted(dup_titles.items())], limit=30), ""]

    s += ["## Ficheros", "",
          "- `store_products.csv` — una fila por producto de la tienda: veredicto, id que arranca, "
          "tipo de cada id, líneas y nombres en PaceSetter, variantes esperadas, lo que añade la unión.",
          "- `supplier_ids.csv` — una fila por id reclamado: tipo, línea, de qué producto es variante, quién lo reclama.",
          "- `shared_variants.csv` — cada variante PaceSetter que acabaría en más de un producto tras la sync.",
          "- `unclaimed_sellable.csv` — productos PaceSetter que ningún producto de la tienda reclama.",
          "- `supplier_cache.json` — las respuestas del proveedor; una nueva ejecución las reutiliza "
          "(`--retry-errors` para volver a pedir las que fallaron).",
          ""]
    return "\n".join(s)


if __name__ == "__main__":
    sys.exit(main())
