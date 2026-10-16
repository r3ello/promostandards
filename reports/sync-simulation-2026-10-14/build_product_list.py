"""One row per PaceSetter product of the store: synced or not, and if not, why and what to do.

Reads a simulate_store_sync.py run (store_products.csv, supplier_ids.csv, unclaimed_sellable.csv,
supplier_cache.json) plus the store (ids for admin links, tags). Writes, next to the run:
  lista-productos-<date>.csv  (';' + BOM, opens in Excel)
  lista-productos-<date>.md   (the counts per reason)

Usage: python reports/sync-simulation-2026-10-14/build_product_list.py reports/sync-simulation-2026-10-14 2026-10-14
"""
import csv
import json
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
from check_discount_metafield import access_token, graphql, local_credentials  # noqa: E402

run, date = Path(sys.argv[1]), sys.argv[2]
rows = list(csv.DictReader(open(run / "store_products.csv", encoding="utf-8-sig"), delimiter=";"))
kind = {r["supplier_id"]: r["kind"] for r in
        csv.DictReader(open(run / "supplier_ids.csv", encoding="utf-8-sig"), delimiter=";")}
unclaimed = list(csv.DictReader(open(run / "unclaimed_sellable.csv", encoding="utf-8-sig"), delimiter=";"))
cache = json.load(open(run / "supplier_cache.json", encoding="utf-8"))

store, cid, secret, version = local_credentials()
token = access_token(store, cid, secret)
admin = {}
cursor = None
while True:
    page = graphql(store, version, token,
                   'query($c:String){products(first:250,after:$c){pageInfo{hasNextPage endCursor}'
                   ' nodes{id handle tags}}}', {"c": cursor})["data"]["products"]
    for n in page["nodes"]:
        admin[n["handle"]] = (n["id"].rsplit("/", 1)[1], n["tags"])
    if not page["pageInfo"]["hasNextPage"]:
        break
    cursor = page["pageInfo"]["endCursor"]

STATUS = {"ACTIVE": "Activo", "DRAFT": "Borrador", "ARCHIVED": "Archivado"}


def has_data(pid):
    p = cache.get("p:" + pid) or {}
    return bool(p.get("parts"))


# store products that point at the same PaceSetter parent -> one fusion group
parents_of = {}
by_parent = defaultdict(set)
for r in rows:
    if r["verdict"] == "UNSYNCABLE_PARTS":
        ps = sorted({x.split("->")[1] for x in r["part_of"].split()})
        parents_of[r["handle"]] = ps
        for p in ps:
            by_parent[p].add(r["handle"])


def classify(r):
    """(grupo, motivo, que hacer)"""
    ids = r["ps_product_ids"].split()
    kinds = {i: kind.get(i, "?") for i in ids}
    synced = r["synced"] == "yes"
    v = r["verdict"]
    if v == "NO_IDS":
        return ("Archivado", "Absorbido por una fusión: sus ids pasaron a otro producto", "Nada")
    if synced and v in ("OK", "MULTI_PRODUCT"):
        dead = [i for i, k in kinds.items() if k not in ("PRODUCT", "PRODUCT_UNLISTED")]
        extra = f" ({len(dead)} id(s) muertos se ignoran: {' '.join(dead)})" if dead else ""
        return ("Sincronizado", "OK" + extra, "Nada")
    if synced:
        bad = " ".join(f"{i}={k}" for i, k in kinds.items())
        return ("Sincronizado, perdió la ficha",
                f"Se sincronizó, pero hoy PaceSetter no da ficha/precio: {bad}",
                "Reclamar a PaceSetter (las próximas pasadas no lo actualizan)")
    if v in ("OK", "MULTI_PRODUCT"):
        return ("Pendiente: bloqueado",
                f"{r['blockers']}. {r['flags']}".strip(". "),
                "Revisar a mano: mezcla familias o comparte familia con otro producto")
    if v == "UNSYNCABLE_PARTS":
        ps = parents_of[r["handle"]]
        others = sorted(set().union(*(by_parent[p] for p in ps)) - {r["handle"]})
        if all(has_data(p) for p in ps):
            return ("Pendiente: fusionar",
                    f"Sus ids son colores/variantes de {' '.join(ps)}; la tienda lo reparte en "
                    f"{len(others) + 1} productos",
                    "Fusionar en un producto con " + (", ".join(others) if others else "su padre (repuntar ids)"))
        missing = [p for p in ps if not has_data(p)]
        return ("Pendiente: PaceSetter sin ficha",
                f"Sus ids son variantes de {' '.join(ps)}, y PaceSetter no devuelve ficha de "
                f"{' '.join(missing)} (lo vende y tiene precio)",
                "Reclamar a PaceSetter la ficha (Product Data) de " + " ".join(missing))
    if v == "UNSYNCABLE":
        if set(kinds.values()) <= {"ORPHAN", "FAMILY_PART", "?"}:
            return ("Descatalogado", "Ningún id existe en PaceSetter (ni vendible, ni ficha, ni precio)",
                    "Hecho: borrador + etiqueta pacesetter-discontinued"
                    if "pacesetter-discontinued" in admin.get(r["handle"], ("", []))[1]
                    else "Pasar a borrador + etiqueta pacesetter-discontinued")
        bad = " ".join(f"{i}={k}" for i, k in kinds.items())
        return ("Pendiente: PaceSetter sin ficha/precio", f"Vendible en PaceSetter pero incompleto: {bad}",
                "Reclamar a PaceSetter ficha/precio")
    return ("?", v, "")


ORDER = ["Sincronizado", "Sincronizado, perdió la ficha", "Pendiente: fusionar", "Pendiente: bloqueado",
         "Pendiente: PaceSetter sin ficha", "Pendiente: PaceSetter sin ficha/precio", "Descatalogado",
         "Archivado", "Nuevo en PaceSetter (no está en la tienda)"]
out = []
for r in rows:
    g, why, todo = classify(r)
    pid = admin.get(r["handle"], ("", []))[0]
    out.append([g, why, todo, r["title"], STATUS.get(r["status"], r["status"]), r["legacy_sku"],
                r["ps_product_ids"], r["handle"],
                f"https://admin.shopify.com/store/trophy-partner/products/{pid}" if pid else ""])
for u in unclaimed:
    if u["verdict"] == "NEW":
        out.append([ORDER[-1], f"PaceSetter lo vende ({u['kind']}) y ningún producto de la tienda lo tiene",
                    "Dejar para el final (crear en lote revisado)", u["name"], "", "", u["supplier_id"], "", ""])
out.sort(key=lambda x: (ORDER.index(x[0]) if x[0] in ORDER else 99, x[3].lower()))

with open(run / f"lista-productos-{date}.csv", "w", newline="", encoding="utf-8-sig") as f:
    w = csv.writer(f, delimiter=";")
    w.writerow(["Grupo", "Motivo", "Qué hacer", "Título", "Estado tienda", "SKU legacy",
                "Ids PaceSetter", "Handle", "Admin"])
    w.writerows(out)

counts = Counter(x[0] for x in out)
md = [f"# Productos PaceSetter en la tienda — {date}", "",
      f"Detalle fila a fila: `lista-productos-{date}.csv` (Excel). Fuente: `simulate_store_sync.py`.", "",
      "| grupo | productos |", "|---|---|"]
md += [f"| {g} | {counts[g]} |" for g in ORDER if counts[g]]
open(run / f"lista-productos-{date}.md", "w", encoding="utf-8").write("\n".join(md) + "\n")
for g in ORDER:
    print(f"{counts[g]:5}  {g}")
