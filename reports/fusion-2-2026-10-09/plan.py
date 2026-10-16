"""Second fusion batch: store listings that are one PaceSetter product.

Reads the read-only simulation of 2026-10-09 and groups every live store listing by the PaceSetter
line(s) it touches (connected components). A group with two or more listings is a merge candidate.
Writes plan.json (input of write.py) and prints a summary.

usage: python plan.py <simulation-dir> <out-dir>
"""
import csv, json, re, sys, collections

SIM, OUT = sys.argv[1], sys.argv[2]
S = list(csv.DictReader(open(f"{SIM}/store_products.csv", encoding="utf-8-sig"), delimiter=";"))
cache = json.load(open(f"{SIM}/supplier_cache.json"))
live = [x for x in S if x["status"] != "ARCHIVED"]

# Left out on purpose: decided elsewhere or waiting on someone.
EXCLUDE_LINES = {"CM711", "CM712", "CM713*", "CM726*", "CM731",          # Polar Camel
                 "CM745*", "CM746*", "CM747*", "CM750", "CM754", "CM816*"}  # blocked 10-06, no Product Data
EXCLUDE_HANDLES = {"p-7964"}  # Crystal Sports Tower, client to confirm


# Every code PaceSetter lists in a line, siblings included, mapped to that line: a listing whose own
# code has no Product Data (CD900USA) or is a part of several (CD953CA -> CD953AK/CD953AL/…) still
# belongs to the line. Missing this left 11 listings behind on 2026-10-09.
line_of = {}
for r in csv.DictReader(open(f"{SIM}/supplier_ids.csv", encoding="utf-8-sig"), delimiter=";"):
    if r["paceSetter_line"]:
        for i in [r["supplier_id"]] + r["part_of"].split():
            line_of.setdefault(i.upper(), r["paceSetter_line"])


def lines(x):
    ls = set(x["paceSetter_lines"].split())
    for t in x["part_of"].split():
        for parent in t.split("->")[1].split("/"):
            ls.add(line_of.get(parent.upper(), parent))
    ls |= {line_of[i.upper()] for i in x["ps_product_ids"].split() if i.upper() in line_of}
    return ls


adj = collections.defaultdict(set)
for x in live:
    ls = lines(x)
    for l in ls:
        adj[l] |= ls
seen, comps = set(), []
for l in sorted(adj):
    if l in seen:
        continue
    st, c = [l], set()
    while st:
        q = st.pop()
        if q not in c:
            c.add(q)
            st.extend(adj[q])
    seen |= c
    comps.append(c)


def has_data(i):
    p, c = cache.get("p:" + i) or {}, cache.get("c:" + i) or {}
    return bool(p.get("parts")) and any(q.get("breaks") for q in c.get("parts", []))


def num(h):
    m = re.match(r"p-(\d+)-", h)
    return int(m.group(1)) if m else 10 ** 9


plan, skipped = [], collections.Counter()
for c in comps:
    members = [x for x in live if lines(x) & c]
    if len(members) < 2:
        continue
    if c & EXCLUDE_LINES or any(x["handle"].startswith(tuple(EXCLUDE_HANDLES)) for x in members):
        skipped["excluded (Polar Camel / blocked / p-7964)"] += 1
        continue
    if all(x["synced"] == "yes" for x in members):
        continue
    # PaceSetter ids that answer as a product: the line itself (CM291*) or the store's own ids (CM289CK).
    product_ids = sorted({l for l in c if has_data(l)})
    store_ids = []
    for x in sorted(members, key=lambda x: num(x["handle"])):
        for i in x["ps_product_ids"].split():
            if i not in store_ids:
                store_ids.append(i)
    live_store_ids = [i for i in store_ids if has_data(i)]
    ids = product_ids or live_store_ids
    if not ids:
        skipped["no product data or prices at PaceSetter"] += 1
        continue
    names = list(dict.fromkeys(((cache.get("p:" + i) or {}).get("name") or "").strip() for i in ids))
    names = [n for n in names if n]
    synced = [x for x in members if x["synced"] == "yes"]
    pool = synced or members
    survivor = sorted(pool, key=lambda x: (x["status"] != "ACTIVE", -len(x["ps_product_ids"].split()),
                                          num(x["handle"])))[0]
    plan.append(dict(lines=sorted(c), ids=ids, names=names, survivor=survivor["handle"],
                     verdicts=sorted({x["verdict"] for x in members}),
                     store=[{k: x[k] for k in ("handle", "title", "status", "ps_product_ids", "legacy_sku",
                                                "synced", "variants_now", "expected_variants")} for x in members]))

plan.sort(key=lambda p: p["lines"][0])
json.dump(plan, open(f"{OUT}/plan.json", "w", encoding="utf-8"), indent=1, ensure_ascii=False)
n_store = sum(len(p["store"]) for p in plan)
print("groups", len(plan), "listings", n_store, "archived after", n_store - len(plan))
print("survivor already synced", sum(1 for p in plan if any(s["synced"] == "yes" and s["handle"] == p["survivor"]
                                                           for s in p["store"])))
print("skipped", dict(skipped))
print(collections.Counter(len(p["store"]) for p in plan))
