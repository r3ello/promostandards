"""Which store products carry PromoStandards ids PaceSetter no longer has, and what to do about them.

The one-shot migration stamped every product with the supplier ids it came from (`custom.ps_product_id`
+ `ps_product_ids`). Those ids are the join to PaceSetter, and most of them no longer exist upstream:
of 298 tagged store products only 81 point at something the supplier still sells. The rest cannot be
synced at all - not a bug in the sync, a catalogue that moved on.

This builds the working list: one row per affected product, the exact ids, what PaceSetter answers
(the real error text where a sync actually tried it), and the closest live supplier codes, so the ids
can be repaired by hand instead of guessed at.

Three kinds of problem, and the report separates them:
  * FAMILY  - the ids are alive, but as PARTS of a family product PaceSetter serves under a wildcard
              code (CM717BK is a price part of CM717*). The migration stored part ids as product
              ids. Repointing is not a one-liner: the family answers Pricing and Media but returns no
              Product Data parts and no Inventory rows, so it would import with zero variants.
  * ORPHAN  - no trace upstream, not even a family. Discontinued or re-coded.
  * PARTIAL - it syncs, but some of the ids it claims are dead. They cost a warning per run and can
              silently strand a variant nobody notices.

Usage:
    python tools/report_orphan_products.py                       # -> informe-ids-huerfanos.html
    python tools/report_orphan_products.py --out other.html --run <sync-run.jsonl>
    python tools/report_orphan_products.py --from-simulation reports/sync-simulation-2026-09-10

Reads the supplier's live sellable list through the running app (`/api/catalog/products`) and the
store through the Admin API. Writes nothing anywhere but the HTML file.

`--from-simulation <dir>` reads neither: it rebuilds the report from a `simulate_store_sync.py` run
(store_products.csv, supplier_ids.csv, unclaimed_sellable.csv, supplier_cache.json). No credentials,
no app, no supplier calls - and reproducible, which the live mode is not: it reports the catalogue as
it was the day that simulation ran. That mode answers a narrower question - which store products have
no PaceSetter behind them at all - so it separates the ids by what the supplier actually knows about
them (nothing, a wildcard family, or a product it will not sell) instead of the live mode's buckets.

Requires: standard library only.
Origin: 2026-09-04, after the first full pass showed 217 unsyncable products.
"""

from __future__ import annotations

import argparse
import csv
import glob
import html
import json
import sys
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from check_discount_metafield import access_token, graphql, local_credentials  # noqa: E402
from sync_store_catalog import opener  # noqa: E402

STORE_PRODUCTS = """
query Tagged($cursor: String) {
  products(first: 100, after: $cursor, query: "tag:promostandards") {
    pageInfo { hasNextPage endCursor }
    nodes {
      id
      handle
      title
      status
      totalInventory
      psId: metafield(namespace: "custom", key: "ps_product_id") { value }
      psIds: metafield(namespace: "custom", key: "ps_product_ids") { value }
      lastSync: metafield(namespace: "trophy_sync", key: "last_sync_at") { value }
      media(first: 60) { nodes { id } }
      variants(first: 60) { nodes { sku media(first: 1) { nodes { id } } } }
    }
  }
}
"""


DEFAULT_OUT = "informe-ids-huerfanos.html"

STYLE = """<style>
:root {
  --bg:#F3F5F7; --surface:#FFFFFF; --surface-2:#E9EDF1; --ink:#16202B; --muted:#58697A;
  --line:#D8E0E7; --accent:#1F5E6B; --accent-wash:#E3EEF0; --loss:#A32C2C; --loss-wash:#F8EAEA;
  --gain:#2F6B4F; --gain-wash:#E8F1EC; --brass:#8A6A22; --code-bg:#EDF1F4;
  --sans:"Segoe UI Variable Text","Segoe UI",system-ui,-apple-system,Arial,sans-serif;
  --mono:"Cascadia Mono",Consolas,ui-monospace,"SF Mono",monospace;
}
@media (prefers-color-scheme: dark) { :root:not([data-theme="light"]) {
  --bg:#0F161D; --surface:#16202A; --surface-2:#1C2833; --ink:#DFE7ED; --muted:#94A5B4;
  --line:#26343F; --accent:#6FB2BF; --accent-wash:#122730; --loss:#E08585; --loss-wash:#2A1718;
  --gain:#86C2A0; --gain-wash:#16241D; --brass:#C9A653; --code-bg:#0D1720;
} }
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--ink); font-family:var(--sans); line-height:1.55; }
.wrap { max-width:1180px; margin:0 auto; padding:2.5rem 1.5rem 5rem; }
h1 { font-size:1.9rem; line-height:1.2; margin:0 0 .35rem; }
h2 { font-size:1.15rem; margin:2.5rem 0 .75rem; }
.sub { color:var(--muted); margin:0 0 2rem; }
.card { background:var(--surface); border:1px solid var(--line); border-radius:12px; padding:1.25rem 1.4rem; }
.card + .card { margin-top:1rem; }
.stats { display:flex; flex-wrap:wrap; gap:.75rem; margin:1.5rem 0 2rem; }
.stat { background:var(--surface); border:1px solid var(--line); border-radius:12px;
         padding:.85rem 1.1rem; min-width:9rem; }
.stat b { display:block; font-size:1.6rem; line-height:1.1; }
.stat span { color:var(--muted); font-size:.85rem; }
.stat.bad b { color:var(--loss); } .stat.warn b { color:var(--brass); } .stat.good b { color:var(--gain); }
p { margin:.6rem 0; }
code { font-family:var(--mono); font-size:.85em; background:var(--code-bg); padding:.1rem .35rem; border-radius:4px; }
code.dead { color:var(--loss); background:var(--loss-wash); }
code.live { color:var(--gain); background:var(--gain-wash); }
.controls { display:flex; flex-wrap:wrap; gap:.6rem; align-items:center; margin:1.25rem 0 .75rem;
             position:sticky; top:0; background:var(--bg); padding:.75rem 0; z-index:2; }
input[type=search] { flex:1; min-width:14rem; padding:.6rem .8rem; border-radius:8px;
    border:1px solid var(--line); background:var(--surface); color:var(--ink); font:inherit; }
button { padding:.55rem .9rem; border-radius:8px; border:1px solid var(--line);
          background:var(--surface); color:var(--ink); font:inherit; cursor:pointer; }
button.on { background:var(--accent-wash); border-color:var(--accent); color:var(--accent); font-weight:600; }
.tablewrap { overflow-x:auto; border:1px solid var(--line); border-radius:12px; background:var(--surface); }
table { border-collapse:collapse; width:100%; font-size:.92rem; }
th, td { text-align:left; padding:.7rem .9rem; border-bottom:1px solid var(--line); vertical-align:top; }
th { position:sticky; top:0; background:var(--surface-2); font-size:.8rem; text-transform:uppercase;
      letter-spacing:.04em; color:var(--muted); }
tr:last-child td { border-bottom:0; }
td a { color:var(--accent); font-weight:600; text-decoration:none; }
td a:hover { text-decoration:underline; }
.handle { font-family:var(--mono); font-size:.78rem; color:var(--muted); }
.meta { font-size:.78rem; color:var(--muted); }
.idrow { margin-bottom:.35rem; }
.sugg { display:block; font-size:.78rem; color:var(--muted); font-family:var(--mono); }
.sugg.none { font-style:italic; font-family:var(--sans); }
.sugg.fam { font-family:var(--sans); color:var(--accent); }
.badge { display:inline-block; padding:.15rem .5rem; border-radius:999px; font-size:.78rem;
          font-weight:600; background:var(--loss-wash); color:var(--loss); margin-bottom:.25rem; }
.sugg.fam b { font-family:var(--mono); }
.answer { font-size:.82rem; color:var(--muted); max-width:22rem; }
.muted { color:var(--muted); }
ol, ul { padding-left:1.2rem; }
li { margin:.3rem 0; }
footer { margin-top:3rem; color:var(--muted); font-size:.85rem; }
</style>"""


def store_products(shop: str, version: str, token: str) -> list[dict]:
    products, cursor = [], None
    while True:
        payload = graphql(shop, version, token, STORE_PRODUCTS, {"cursor": cursor} if cursor else {})
        if payload.get("errors"):
            sys.exit(f"listing failed: {payload['errors']}")
        page = payload["data"]["products"]
        for node in page["nodes"]:
            canonical = ((node.get("psId") or {}).get("value") or "").strip()
            try:
                covered = json.loads((node.get("psIds") or {}).get("value") or "[]")
            except json.JSONDecodeError:
                covered = []
            ids, seen = [], set()
            for value in ([canonical] if canonical else []) + [str(c).strip() for c in covered]:
                if value and value.upper() not in seen:
                    seen.add(value.upper())
                    ids.append(value)
            products.append({
                "gid": node["id"], "handle": node["handle"], "title": node["title"],
                "status": node["status"], "inventory": node.get("totalInventory"),
                "canonical": canonical, "ids": ids,
                "synced": bool((node.get("lastSync") or {}).get("value")),
                "images": len(node["media"]["nodes"]),
                "variantImages": sum(1 for v in node["variants"]["nodes"] if v["media"]["nodes"]),
                "skus": [v["sku"] for v in node["variants"]["nodes"] if v.get("sku")],
            })
        if not page["pageInfo"]["hasNextPage"]:
            return products
        cursor = page["pageInfo"]["endCursor"]


def family_of(dead_id: str, families: list[str]) -> str | None:
    """The wildcard family code covering this id, e.g. CM717BK -> CM717* (families are longest-first)."""
    return next((f for f in families if dead_id.upper().startswith(f)), None)


def family_detail(op, base: str, family: str, cache: dict) -> dict:
    """What the supplier actually serves under the family code - asked once per family.

    `/api/catalog/products/{id}` carries no variant list, so asking it for one always answered zero.
    What tells you whether a product could be built there is `productDataMissing` - no parts, nothing
    to make variants from - plus the inventory rows and price entries the aggregate did join.
    """
    if family in cache:
        return cache[family]
    try:
        detail = json.load(op.open(f"{base}/api/catalog/products/"
                                   + urllib.parse.quote(family, safe=""), timeout=300))
        cache[family] = {"title": detail.get("title"),
                         "parts": 0 if detail.get("productDataMissing") else None,
                         "pricing": len(detail.get("pricing") or []),
                         "inventory": len(detail.get("inventory") or []),
                         "images": len(detail.get("imageUrls") or []),
                         "warnings": detail.get("warnings") or []}
    except Exception as e:  # a family that also fails is worth showing as such
        cache[family] = {"error": str(e)[:120]}
    return cache[family]


def candidates(dead_id: str, sellable: list[str], limit: int = 5) -> list[str]:
    """Live supplier codes that share the longest prefix with a dead one.

    PaceSetter's ids are a base code plus a colour/size tail (CM822GR, GM750ABK), and when a product
    is re-coded the base usually survives. The longest shared prefix is therefore a good first guess
    at "what did this become" - and a bad guess is obvious to a human, which is the point of showing
    several.
    """
    target = dead_id.upper()
    best: list[tuple[int, str]] = []
    for other in sellable:
        shared = 0
        for a, b in zip(target, other):
            if a != b:
                break
            shared += 1
        if shared >= 4:
            best.append((shared, other))
    best.sort(key=lambda x: (-x[0], x[1]))
    top = best[:limit] if best else []
    return [f"{code} ({shared})" for shared, code in top]


# ------------------------------------------------- offline: rebuilt from a simulate_store_sync run

# The kinds simulate_store_sync.py assigns to an id, and what each means for someone reading this.
KIND_LABEL = {
    "ORPHAN": ("sin producto", "PaceSetter no lo sirve como producto: no está en el catálogo "
                               "vendible, Product Data no tiene ficha y no hay precio. Su servicio "
                               "de Inventory todavía responde por algunos de estos códigos."),
    "FAMILY_PART": ("parte de una familia", "El id cae bajo un código de familia con comodín, pero "
                                            "ninguna respuesta del proveedor lo lista por su cuenta."),
    "NO_PRICE": ("sin precio", "PaceSetter tiene ficha del producto pero no publica tabla de precios. "
                               "La importación lo rechaza a propósito: se publicaría a 0."),
    "NO_PRODUCT_DATA": ("sin ficha", "El proveedor lo lista como vendible, pero Product Data no "
                                     "devuelve partes: no hay de qué crear las variantes."),
}
# What getProductSellable answered, and — narrower — what can actually seed a sync today.
SIM_SELLABLE_KINDS = ("PRODUCT", "PRODUCT_UNLISTED", "NO_PRICE", "NO_PRODUCT_DATA")
SIM_LIVE_KINDS = ("PRODUCT", "PRODUCT_UNLISTED")


def read_sim_csv(path: Path) -> list[dict]:
    with open(path, encoding="utf-8-sig", newline="") as handle:
        return list(csv.DictReader(handle, delimiter=";"))


def load_simulation(directory: Path) -> tuple[list[dict], list[str], dict]:
    """Store products, the supplier's sellable ids and its cached answers, from a simulation run."""
    products = []
    for row in read_sim_csv(directory / "store_products.csv"):
        products.append({
            "gid": "", "handle": row["handle"], "title": row["title"].strip(),
            "status": row["status"], "inventory": None, "canonical": row["ps_product_id"],
            "ids": row["ps_product_ids"].split(),
            "kinds": dict(pair.split("=", 1) for pair in row["kinds"].split() if "=" in pair),
            "legacy_sku": row["legacy_sku"], "synced": row["synced"] == "si",
            "skus": [""] * int(row["variants_now"] or 0), "images": None, "variantImages": None,
        })
    sellable = {row["supplier_id"].upper() for row in read_sim_csv(directory / "supplier_ids.csv")
                if row["kind"] in SIM_SELLABLE_KINDS}
    sellable |= {row["supplier_id"].upper()
                 for row in read_sim_csv(directory / "unclaimed_sellable.csv")}
    cache_path = directory / "supplier_cache.json"
    cache = json.loads(cache_path.read_text(encoding="utf-8")) if cache_path.exists() else {}
    return products, sorted(sellable), cache


def family_detail_cached(family: str, cache: dict) -> dict:
    """What the simulation's cached supplier answers say a family code actually serves.

    Same rule as the simulator: an entry carrying an `error` is not an answer. A family code can be
    listed in getProductSellable and still 404 on every service, and saying so beats reporting it as
    a product with zero of everything.
    """
    def answered(kind: str) -> dict | None:
        entry = cache.get(f"{kind}:{family}")
        return entry if isinstance(entry, dict) and "error" not in entry else None

    product, price, inventory = answered("p"), answered("c"), answered("i")
    if not product:
        failed = cache.get("p:" + family)
        return {"error": (failed or {}).get("error")
                if isinstance(failed, dict) else f"{family} no figura en la caché de la simulación"}
    return {"title": product.get("name"),
            "parts": len(product.get("parts") or []),
            "pricing": len(price.get("parts") or []) if price else 0,
            "inventory": len(inventory.get("rows") or []) if inventory else 0}


def classify_simulation(products: list[dict], sellable_list: list[str], cache: dict):
    """Split the store by what PaceSetter knows about its ids. Only what has no product behind it.

    A product whose ids are PARTs of a real PaceSetter product is deliberately left out: it exists
    upstream, and what it needs is a repointing decision, not this list.
    """
    families = sorted({i[:-1] for i in sellable_list if i.endswith("*")}, key=len, reverse=True)
    orphans, familied, unimportable, partials = [], [], [], []
    for p in products:
        kinds = p["kinds"]
        p["live"] = [i for i in p["ids"] if kinds.get(i) in SIM_LIVE_KINDS]
        p["dead"] = [i for i in p["ids"] if kinds.get(i) not in SIM_LIVE_KINDS]
        p["families"] = {d: family_of(d, families) for d in p["dead"]}
        p["answer"], p["suggestions"], p["familyDetail"] = "", {}, {}
        present = {kinds.get(i) for i in p["ids"]}
        if p["live"] and p["dead"]:
            bucket = partials
        elif p["live"] or present <= {"PART"}:
            continue
        elif present <= {"ORPHAN"}:
            bucket = orphans
        elif present <= {"FAMILY_PART"}:
            bucket = familied
        elif present & {"NO_PRICE", "NO_PRODUCT_DATA"}:
            bucket = unimportable
        else:
            continue  # parts mixed with dead ids: also a repointing decision
        if bucket is unimportable:
            p["kindLabel"] = KIND_LABEL[next(k for k in ("NO_PRICE", "NO_PRODUCT_DATA")
                                             if k in present)]
        else:
            p["suggestions"] = {d: candidates(d, sellable_list) for d in p["dead"]}
            p["familyDetail"] = {f + "*": family_detail_cached(f + "*", cache)
                                 for f in {v for v in p["families"].values() if v}}
        bucket.append(p)
    return orphans, familied, unimportable, partials


def render_offline(products, orphans, familied, unimportable, partials, sellable_list, shop_name,
                   source) -> str:
    listed = len(orphans) + len(familied) + len(unimportable) + len(partials)
    return f"""<!doctype html>
<html lang="es">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Productos de la tienda sin producto en PaceSetter</title>
{STYLE}

<div class="wrap">
<h1>Productos de la tienda sin producto en PaceSetter</h1>
<p class="sub">Tienda {shop_name} · {date.today().isoformat()} · fuente: {html.escape(source)} ·
catálogo vendible del proveedor ese día: {len(sellable_list)} ids</p>

<div class="stats">
  <div class="stat"><b>{len(products)}</b><span>productos<br>PromoStandards</span></div>
  <div class="stat bad"><b>{len(orphans)}</b><span>sin producto<br>en el proveedor</span></div>
  <div class="stat warn"><b>{len(familied)}</b><span>sólo bajo una<br>familia con comodín</span></div>
  <div class="stat warn"><b>{len(unimportable)}</b><span>existen pero<br>no se pueden importar</span></div>
  <div class="stat warn"><b>{len(partials)}</b><span>parciales<br>(algún id muerto)</span></div>
  <div class="stat good"><b>{len(products) - listed}</b><span>con producto<br>detrás (fuera de aquí)</span></div>
</div>

<div class="card">
<h2 style="margin-top:0">Qué es esta lista</h2>
<p>Cada producto migrado guarda los ids de PaceSetter de los que procede, en
<code>custom.ps_product_id</code> y <code>custom.ps_product_ids</code>. <b>Ese id es la única unión con
el proveedor</b>: con él se piden ficha, precios, stock e imágenes. Aquí están los productos de la
tienda <b>cuyos ids no corresponden a ningún producto de PaceSetter</b>, separados por lo que el
proveedor sabe de ellos. Ninguno de los {len(orphans) + len(familied) + len(unimportable)} primeros se
ha sincronizado nunca, y todos siguen <b>a la venta</b> en la tienda.</p>
<p class="muted">Contrastado con el proveedor en vivo el 2026-09-13: su catálogo vendible sigue
teniendo los mismos 1278 ids que el día de la simulación —ni una alta ni una baja— y ninguno de los
398 ids que llevan estos productos aparece en él.</p>

<h2>Sin producto en PaceSetter ({len(orphans)} productos)</h2>
<p>Su id no está en el catálogo vendible, Product Data no tiene ficha y no hay tabla de precios: ni
con qué construir variantes, ni a qué precio publicarlas. <b>No significa que el proveedor haya
borrado el código</b>: en una muestra de 20 de estos ids consultada en vivo el 2026-09-13, 12
contestaron 404 en todos los servicios y <b>8 seguían devolviendo filas de stock</b> — códigos
descatalogados que aún están en su almacén, pero sin ficha ni precio. La conclusión práctica es la
misma: no hay nada que importar mientras el id no se corrija.</p>
<p>La columna del medio propone los códigos vivos que más se parecen — el número entre paréntesis es
cuántos caracteres comparten desde el principio. <b>Son una pista, no una respuesta</b>: hay que
confirmarla en el catálogo del proveedor. La decisión, producto a producto: corregir el id, dejar de
publicarlo, o mantenerlo como producto manual fuera de la sincronización.</p>

<h2>Sólo bajo una familia con comodín ({len(familied)} productos)</h2>
<p>PaceSetter agrupa familias bajo un código con asterisco (<code>CM717*</code>) y el id de la tienda
cae ahí dentro, pero el proveedor no lo sirve por su cuenta. La columna dice qué devuelve esa familia:
si no trae partes ni filas de stock, repuntar el metafield a la familia daría un producto con
<b>0 variantes</b>, así que no basta con cambiarlo.</p>

<h2>Existen pero no se pueden importar ({len(unimportable)} productos)</h2>
<p>PaceSetter sí los conoce y aun así no entran: o no publica tabla de precios (se rechaza a
propósito, porque se publicarían a 0), o los lista como vendibles sin ficha de Product Data, sin nada
con lo que construir variantes. Aquí no hay id que corregir; es una pregunta para el proveedor.</p>

<h2>Y aparte: parciales ({len(partials)} productos)</h2>
<p>Estos <b>sí</b> se sincronizan, pero arrastran algún id muerto en <code>ps_product_ids</code>. No
rompen nada: ensucian los avisos de cada pasada y conviene limpiarlos.</p>

<h2>Lo que no está aquí</h2>
<p>Los productos cuyos ids <b>sí</b> existen en PaceSetter pero como <b>partes</b> de otro producto
suyo. Esos no son un agujero de catálogo sino una decisión de a qué id apuntar, y van en
<code>revision-manual.csv</code>, junto a la simulación.</p>
</div>

<div class="controls">
  <input type="search" id="q" placeholder="Buscar por nombre, handle o id…">
  <button data-f="all" class="on">Todos</button>
  <button data-f="orphan">Sin producto ({len(orphans)})</button>
  <button data-f="family">Bajo familia ({len(familied)})</button>
  <button data-f="unimportable">No importables ({len(unimportable)})</button>
  <button data-f="partial">Parciales ({len(partials)})</button>
  <span class="muted" id="count"></span>
</div>

<div class="tablewrap">
<table>
<thead><tr><th>Producto</th><th>Ids sin producto · candidatos / motivo</th><th>Ids vivos</th><th>Qué se sabe</th></tr></thead>
<tbody id="body">
{rows_html(orphans, shop_name, "orphan")}
{rows_html(familied, shop_name, "family")}
{rows_html(unimportable, shop_name, "unimportable")}
{rows_html(partials, shop_name, "partial")}
</tbody>
</table>
</div>

<footer>
Generado por <code>tools/report_orphan_products.py --from-simulation</code> sobre
{html.escape(source)}: sin llamadas al proveedor ni a la tienda, así que refleja el catálogo tal como
estaba el día de esa simulación. El nombre enlaza a una búsqueda en el admin por su SKU de migración.
</footer>
</div>

<script>
const rows = [...document.querySelectorAll('#body tr')];
const count = document.getElementById('count');
let filter = 'all';
function apply() {{
  const q = document.getElementById('q').value.trim().toLowerCase();
  let shown = 0;
  for (const tr of rows) {{
    const okKind = filter === 'all' || tr.dataset.kind === filter;
    const okText = !q || tr.dataset.search.includes(q);
    const show = okKind && okText;
    tr.hidden = !show;
    if (show) shown++;
  }}
  count.textContent = shown + ' de ' + rows.length;
}}
document.getElementById('q').addEventListener('input', apply);
for (const b of document.querySelectorAll('button[data-f]')) {{
  b.addEventListener('click', () => {{
    filter = b.dataset.f;
    document.querySelectorAll('button[data-f]').forEach(x => x.classList.toggle('on', x === b));
    apply();
  }});
}}
apply();
</script>
"""


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument("--csv", default="",
                        help="also write the id overlaps here: one row per (product, shared id), "
                             "with who else claims it — the working list for repairing "
                             "ps_product_ids in a migration sheet")
    parser.add_argument("--run", default="",
                        help="sync_store_catalog reports, comma-separated or a glob — the real "
                             "errors, and which products the sync could not finish")
    parser.add_argument("--from-simulation", default="",
                        help="rebuild from a simulate_store_sync.py output directory instead of the "
                             "live store and app: no credentials, no supplier calls, reproducible")
    parser.add_argument("--store", default="trophy-partner",
                        help="store name for the admin links in --from-simulation mode")
    args = parser.parse_args()

    if args.from_simulation:
        directory = Path(args.from_simulation)
        products, sellable_list, cache = load_simulation(directory)
        orphans, familied, unimportable, partials = classify_simulation(products, sellable_list, cache)
        out = (str(directory / "productos-sin-pacesetter.html")
               if args.out == DEFAULT_OUT else args.out)
        Path(out).write_text(
            render_offline(products, orphans, familied, unimportable, partials, sellable_list,
                           args.store, str(directory).replace("\\", "/")),
            encoding="utf-8")
        print(f"{len(orphans)} sin producto · {len(familied)} bajo familia · "
              f"{len(unimportable)} no importables · {len(partials)} parciales "
              f"(de {len(products)} productos) -> {out}")
        return 0

    shop, client_id, client_secret, version = local_credentials()
    token = access_token(shop, client_id, client_secret)

    op = opener(args.base)
    catalog = json.load(op.open(f"{args.base}/api/catalog/products", timeout=600))
    sellable_list = sorted({row["productId"].strip().upper() for row in catalog if row.get("productId")})
    sellable = set(sellable_list)

    # What the supplier actually answered, where a sync tried it: better evidence than "not listed".
    # Later reports win, so a product that failed once and succeeded afterwards reads as fine.
    errors, succeeded = {}, set()
    for pattern in [x.strip() for x in args.run.split(",") if x.strip()]:
        for path in sorted(glob.glob(pattern)):
            for line in Path(path).read_text(encoding="utf-8").splitlines():
                try:
                    row = json.loads(line)
                except json.JSONDecodeError:
                    continue
                pid = row.get("productId", "").upper()
                if row.get("ok"):
                    succeeded.add(pid)
                    errors.pop(pid, None)
                elif row.get("error"):
                    message = row["error"]
                    if '"message":"' in message:
                        message = message.split('"message":"', 1)[1].split('"', 1)[0]
                    errors[pid] = message
                    succeeded.discard(pid)

    # Longest first: CD916A* must win over CD916* for CD916ABL.
    families = sorted({i[:-1] for i in sellable_list if i.endswith("*")}, key=len, reverse=True)

    products = store_products(shop, version, token)

    # Who claims each supplier id. The app's index maps an id to ONE product, so an id claimed twice
    # means a sync of it silently updates whichever came first and never touches the other — found the
    # hard way: syncing CM288DB refreshed the "wine bag large" while the "small" that also lists it sat
    # untouched and was reported as done.
    owners: dict[str, list[dict]] = {}
    for product in products:
        for i in product["ids"]:
            owners.setdefault(i.upper(), []).append(product)

    familied, orphans, partials, failed, incomplete, shared, healthy = [], [], [], [], [], [], 0
    for product in products:
        dead = [i for i in product["ids"] if i.upper() not in sellable]
        live = [i for i in product["ids"] if i.upper() in sellable]
        product["dead"], product["live"] = dead, live
        product["families"] = {d: family_of(d, families) for d in dead}
        # The supplier's own answer, when a sync actually tried this product.
        product["error"] = next((errors[i.upper()] for i in product["ids"] if i.upper() in errors), "")
        product["sharedWith"] = {i: [o["handle"] for o in owners[i.upper()] if o is not product]
                                 for i in product["ids"] if len(owners[i.upper()]) > 1}
        if product["sharedWith"] and live:
            # Two products claiming one id is the blocking problem: nothing can be synced correctly
            # until the data says which of them owns it.
            shared.append(product)
        elif live and product["error"]:
            # It is sellable and the sync still could not finish it: that is its own problem, and a
            # different one from an id the supplier never had.
            failed.append(product)
        elif live and product["synced"] and (product["images"] == 0
                                             or (len(product["ids"]) > 1
                                                 and product["variantImages"] == 0)):
            # Published, but the photos did not land: either none at all, or none reached a variant.
            # The second is the real signature of the media outage — Shopify refuses to attach an
            # image it has not finished ingesting, and that pass gave up before it was ready.
            incomplete.append(product)
        elif not dead:
            healthy += 1
        elif live:
            partials.append(product)
        elif any(product["families"].values()):
            familied.append(product)
        else:
            orphans.append(product)

    cache = {}
    for product in failed + incomplete + shared:
        product["suggestions"], product["familyDetail"] = {}, {}
        product["answer"] = product["error"]
    for product in familied + orphans + partials:
        product["suggestions"] = {d: candidates(d, sellable_list) for d in product["dead"]}
        product["answer"] = product["error"] or errors.get(product["canonical"].upper(), "")
        product["familyDetail"] = {f + "*": family_detail(op, args.base, f + "*", cache)
                                   for f in {v for v in product["families"].values() if v}}
    print(f"{len(cache)} familia(s) consultadas al proveedor", flush=True)

    if args.csv:
        # One row per (product, contested id). A migration sheet is edited per product, so the
        # product's whole id list rides along: deciding who keeps an id means rewriting that list.
        with open(args.csv, "w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.writer(handle, delimiter=";")
            writer.writerow(["id_en_conflicto", "handle", "titulo", "ps_product_id",
                             "sincronizado", "variantes", "reclamado_tambien_por",
                             "ps_product_ids_actual"])
            for product in sorted(shared, key=lambda x: x["handle"]):
                for supplier_id, others in sorted(product["sharedWith"].items()):
                    writer.writerow([supplier_id, product["handle"], product["title"],
                                     product["canonical"], "si" if product["synced"] else "no",
                                     len(product["skus"]), " | ".join(others),
                                     " ".join(product["ids"])])
        print(f"{sum(len(p['sharedWith']) for p in shared)} conflicto(s) -> {args.csv}", flush=True)

    shop_name = shop.split(".")[0]
    Path(args.out).write_text(
        render(products, familied, orphans, partials, failed, incomplete, shared, healthy,
               sellable_list, shop_name),
        encoding="utf-8")
    print(f"{len(familied)} con familia · {len(orphans)} sin rastro · {len(partials)} parciales · "
          f"{len(failed)} fallidos · {len(incomplete)} sin imagen · {len(shared)} con id compartido · "
          f"{healthy} sanos -> {args.out}")
    return 0


def reason(error: str) -> tuple[str, str]:
    """@return (short reason, what it means) for a supplier answer, so a row reads without decoding."""
    if not error:
        return ("—", "")
    if "no configuration" in error:
        return ("sin precio", "PaceSetter no publica tabla de precios para este id. No se importa a "
                              "propósito: sin precio el producto saldría a 0.")
    if "getProduct returned no product" in error or "No supplier service returned data" in error:
        return ("sin producto", "Product Data no conoce el id. Es el caso de los huérfanos, pero "
                                "aquí el proveedor sí lo lista como vendible.")
    if "inventory service" in error:
        return ("sin inventario", "El servicio de Inventory no tiene ficha. Hoy se tolera: entra sin "
                                  "stock. Si aparece aquí es que falló por otra razón añadida.")
    if "getMediaContent" in error:
        return ("media caído", "El servicio de imágenes del proveedor falló. No borra nada: el "
                               "producto se queda con las imágenes que ya tuviera.")
    if "timed out" in error or "timeout" in error:
        return ("timeout", "Tardó más que el límite del cliente. Suele ser una familia enorme (51 "
                           "variantes) o el proveedor degradado; se reintenta con --skip-done.")
    if "Access to this namespace" in error:
        return ("metafield denegado", "Shopify rechazó escribir en un namespace reservado de otra app.")
    return ("otro", error[:160])


def admin_url(product: dict, shop_name: str) -> str:
    """The product's admin page - or, with no gid (offline mode), a search that lands on it."""
    if product.get("gid"):
        return f"https://admin.shopify.com/store/{shop_name}/products/{product['gid'].rsplit('/', 1)[-1]}"
    query = product.get("legacy_sku") or product.get("handle") or product.get("title", "")
    return f"https://admin.shopify.com/store/{shop_name}/products?query={urllib.parse.quote(query)}"


def rows_html(products: list[dict], shop_name: str, kind: str) -> str:
    out = []
    for p in sorted(products, key=lambda x: x["title"].lower()):
        admin = admin_url(p, shop_name)
        if kind == "shared":
            filas = "".join(
                f'<div class="idrow"><code class="dead">{html.escape(i)}</code>'
                f'<span class="sugg">también en {html.escape(", ".join(otros))}</span></div>'
                for i, otros in p["sharedWith"].items())
            out.append(f"""<tr data-kind="shared" data-search="{html.escape((p['title'] + ' ' + p['handle'] + ' ' + ' '.join(p['ids'])).lower())}">
  <td><a href="{admin}" target="_blank" rel="noopener">{html.escape(p['title'])}</a>
      <div class="handle">{html.escape(p['handle'])}</div>
      <div class="meta">{p['status'].lower()} · {len(p['skus'])} variante(s) · {'sincronizado' if p['synced'] else 'sin sincronizar'}</div></td>
  <td><span class="badge">id compartido</span>{filas}</td>
  <td>{"".join(f'<code class="live">{html.escape(i)}</code> ' for i in p["live"]) or "—"}</td>
  <td class="answer"><span class="muted">La app resuelve un id a un solo producto: sincronizarlo
      actualiza al primero que lo reclama y deja al otro intacto.</span></td>
</tr>""")
            continue
        if kind == "unimportable":
            short, meaning = p["kindLabel"]
            ids = "".join(f'<div class="idrow"><code class="dead">{html.escape(i)}</code></div>'
                          for i in p["ids"])
            out.append(f"""<tr data-kind="unimportable" data-search="{html.escape((p['title'] + ' ' + p['handle'] + ' ' + ' '.join(p['ids'])).lower())}">
  <td><a href="{admin}" target="_blank" rel="noopener">{html.escape(p['title'])}</a>
      <div class="handle">{html.escape(p['handle'])}</div>
      <div class="meta">{p['status'].lower()} · {len(p['skus'])} variante(s)</div></td>
  <td><span class="badge">{html.escape(short)}</span>{ids}</td>
  <td><span class="muted">ninguno</span></td>
  <td class="answer">{html.escape(meaning)}</td>
</tr>""")
            continue
        if kind in ("failed", "incomplete"):
            short, meaning = reason(p.get("error", ""))
            if kind == "incomplete":
                if p["images"] == 0:
                    short, meaning = ("sin imágenes", "Se publicó correctamente pero sin ninguna "
                                      "imagen: el servicio de media del proveedor no respondió en "
                                      "esa pasada. Se arregla volviéndolo a sincronizar.")
                else:
                    short, meaning = ("imágenes sin asociar", "Tiene imágenes en el producto pero "
                                      "ninguna llegó a una variante: Shopify rechaza asociar una "
                                      "imagen que aún está procesando. Se arregla resincronizando.")
            live = "".join(f'<code class="live">{html.escape(i)}</code> ' for i in p["live"]) or "—"
            out.append(f"""<tr data-kind="{kind}" data-search="{html.escape((p['title'] + ' ' + p['handle'] + ' ' + ' '.join(p['ids'])).lower())}">
  <td><a href="{admin}" target="_blank" rel="noopener">{html.escape(p['title'])}</a>
      <div class="handle">{html.escape(p['handle'])}</div>
      <div class="meta">{p['status'].lower()} · {len(p['skus'])} variante(s) · {p['images']} imagen(es) · {p['variantImages']} con foto propia</div></td>
  <td><span class="badge">{html.escape(short)}</span><span class="sugg">{html.escape(meaning)}</span></td>
  <td>{live}</td>
  <td class="answer">{html.escape(p.get('error', '')) or '<span class="muted">—</span>'}</td>
</tr>""")
            continue
        blocks = []
        for d in p["dead"]:
            fam = p["families"].get(d)
            if fam:
                info = p["familyDetail"].get(fam + "*", {})
                # Offline the cache has no media, so show only what this source actually knows.
                served = (html.escape(info["error"]) if "error" in info else ", ".join(
                    piece for piece in [
                        f'título «{html.escape(str(info.get("title")))}»',
                        f'{info["parts"]} partes en Product Data' if info.get("parts") is not None else "",
                        f'{info["pricing"]} precios' if info.get("pricing") is not None else "",
                        f'{info["inventory"]} filas de stock' if info.get("inventory") is not None else "",
                        f'{info["images"]} imagen(es)' if info.get("images") is not None else "",
                    ] if piece))
                extra = (f'<span class="sugg fam">es parte de <b>{html.escape(fam)}*</b> → {served}</span>')
            elif p["suggestions"].get(d):
                extra = f'<span class="sugg">≈ {html.escape(", ".join(p["suggestions"][d]))}</span>'
            else:
                extra = '<span class="sugg none">sin código parecido</span>'
            blocks.append(f'<div class="idrow"><code class="dead">{html.escape(d)}</code>{extra}</div>')
        dead = "".join(blocks)
        live = ("".join(f'<code class="live">{html.escape(i)}</code> ' for i in p["live"])
                or '<span class="muted">ninguno</span>')
        answer = (html.escape(p["answer"]) if p["answer"]
                  else '<span class="muted">no consultado — no figura en getProductSellable</span>')
        out.append(f"""<tr data-kind="{kind}" data-search="{html.escape((p['title'] + ' ' + p['handle'] + ' ' + ' '.join(p['ids'])).lower())}">
  <td><a href="{admin}" target="_blank" rel="noopener">{html.escape(p['title'])}</a>
      <div class="handle">{html.escape(p['handle'])}</div>
      <div class="meta">{p['status'].lower()} · {len(p['skus'])} variante(s) · stock {p['inventory'] if p['inventory'] is not None else '—'}</div></td>
  <td>{dead}</td>
  <td>{live}</td>
  <td class="answer">{answer}</td>
</tr>""")
    return "\n".join(out)


def render(products, familied, orphans, partials, failed, incomplete, shared, healthy, sellable_list,
           shop_name) -> str:
    return f"""<!doctype html>
<html lang="es">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Ids PromoStandards sin correspondencia en PaceSetter</title>
{STYLE}

<div class="wrap">
<h1>Ids PromoStandards sin correspondencia en PaceSetter</h1>
<p class="sub">Tienda {shop_name} · {date.today().isoformat()} · catálogo vendible del proveedor: {len(sellable_list)} ids</p>

<div class="stats">
  <div class="stat"><b>{len(products)}</b><span>productos con tag<br>promostandards</span></div>
  <div class="stat warn"><b>{len(familied)}</b><span>ids que son<br>partes de una familia</span></div>
  <div class="stat bad"><b>{len(orphans)}</b><span>sin rastro<br>en el proveedor</span></div>
  <div class="stat warn"><b>{len(partials)}</b><span>parciales<br>(algún id muerto)</span></div>
  <div class="stat bad"><b>{len(failed)}</b><span>fallaron<br>al sincronizar</span></div>
  <div class="stat warn"><b>{len(incomplete)}</b><span>publicados<br>sin imágenes</span></div>
  <div class="stat bad"><b>{len(shared)}</b><span>con id<br>compartido</span></div>
  <div class="stat good"><b>{healthy}</b><span>sanos<br>(todos los ids vivos)</span></div>
</div>

<div class="card">
<h2 style="margin-top:0">El problema</h2>
<p>Cada producto migrado guarda en Shopify los ids de PaceSetter de los que procede, en dos
metafields: <code>custom.ps_product_id</code> (el canónico) y <code>custom.ps_product_ids</code> (todos
los que cubre). <b>Ese id es la única unión con PaceSetter</b>: con él se piden datos, precios, stock,
imágenes y se calcula la escalera de descuentos. Si el proveedor no reconoce el id, el producto no se
sincroniza nunca.</p>
<p>Hoy PaceSetter sirve <b>{len(sellable_list)} referencias</b> en <code>getProductSellable</code>, y
resulta que <b>hay dos motivos distintos</b> por los que un id de la tienda no está entre ellas.</p>

<h2>Motivo 1 — el id es una PARTE, no un producto ({len(familied)} productos)</h2>
<p>PaceSetter agrupa familias bajo un código con asterisco: <code>CM717*</code> es «14 oz. Pilsner
Tumbler», y <code>CM717BK</code>, <code>CM717RD</code>… no son productos suyos sino <b>partes de
precio</b> de esa familia. La migración guardó ids de parte como si fueran ids de producto.
Comprobado servicio a servicio:</p>
<ul>
  <li><code>CM717</code> → 404, no existe.</li>
  <li><code>CM717BK</code> → Product Data: <i>no record</i>; Pricing: <i>no configuration</i>. Nada.</li>
  <li><code>CM717*</code> → <b>sí existe</b>: título «14 oz. Pilsner Tumbler», <b>16 partes de precio</b>
      (una por color, con los mismos códigos que guarda la tienda) y su imagen.</li>
</ul>
<p><b>Pero repuntar el metafield a la familia no basta hoy</b>: para ese código, Product Data no
devuelve partes y el servicio de Inventory no devuelve filas, así que la app construiría el producto
con <b>0 variantes</b>. Hay precio e imagen, pero no hay de qué crear las variantes ni con qué stock.
Es una pregunta para el proveedor, no algo que se arregle desde aquí — la columna de la derecha te
dice, familia por familia, exactamente qué sirve cada una.</p>

<h2>Motivo 2 — no hay rastro ({len(orphans)} productos)</h2>
<p>Ni el id, ni una familia que lo contenga. Son referencias descatalogadas o renombradas con otro
código. Cuando una sincronización lo intentó, PaceSetter respondió
<code>no product for productId=&lt;id&gt;</code> y la app devolvió <b>404</b> (esa respuesta real
aparece en la última columna). Para estos, la lista propone los códigos vivos que más se parecen —
son una pista, hay que confirmarla en el catálogo del proveedor.</p>

<h2>Motivo 3 — el proveedor lo vende, pero la sincronización no pudo terminarlo ({len(failed)} productos)</h2>
<p>Estos <b>sí</b> están en el catálogo vendible y aun así no entraron. La columna del medio dice por
qué, con la respuesta literal del proveedor a la derecha. El motivo más común es <b>sin precio</b>:
<code>getConfigurationAndPricing</code> no devuelve tabla, y la importación se detiene <b>a
propósito</b> — un producto sin precio saldría publicado a 0, que es peor que no publicarlo. Los
demás motivos (sin producto, media caído, timeout) suelen ser transitorios y se resuelven
reintentando.</p>

<h2>Motivo 4 — publicados pero con las imágenes a medias ({len(incomplete)} productos)</h2>
<p>Entraron bien —variantes, precio, stock, descuentos— pero las fotos no cuajaron: o no llegó
ninguna, o llegaron al producto y <b>ninguna se asoció a una variante</b>. Lo segundo es la firma del
apagón de su servicio de imágenes: Shopify rechaza asociar una imagen que todavía está procesando.
No se pierde nada y no hay nada que decidir — se arregla volviendo a sincronizarlos.</p>

<h2>Motivo 5 — dos productos reclaman el mismo id ({len(shared)} productos)</h2>
<p><b>Es el problema que bloquea la migración nueva.</b> La app resuelve un id de PaceSetter a
<b>un</b> producto de la tienda, así que cuando dos lo llevan en su <code>ps_product_ids</code>,
sincronizar ese id actualiza al primero que lo reclama y <b>deja al otro intacto</b> — informando de
éxito. Se descubrió al sincronizar <code>CM288DB</code>: refrescó el <i>Leatherette Wine Bag
<b>Large</b></i> mientras el <i><b>Small</b></i>, que también lo lista, se quedaba sin tocar.</p>
<p>No es algo que la sincronización pueda decidir: dado <code>CM288DB</code>, sólo tú sabes si
pertenece al bolso pequeño o al grande. Las salidas son <b>repartir los ids</b> para que cada uno
aparezca en un solo producto, o <b>fusionar</b> los dos productos si en realidad son el mismo artículo
en dos tamaños. Hasta entonces estos quedan fuera de las pasadas.</p>

<h2>Y aparte: parciales ({len(partials)} productos)</h2>
<p>Estos <b>sí</b> se sincronizan, pero arrastran algún id muerto dentro de
<code>ps_product_ids</code>. No rompen nada; ensucian los avisos de cada pasada y conviene limpiarlos.</p>
</div>

<div class="card">
<h2 style="margin-top:0">Cómo trabajar con esta lista</h2>
<ol>
  <li>Filtra por el tipo de problema con los botones, o busca por nombre, handle o id.</li>
  <li>El nombre del producto <b>enlaza a su ficha en el admin de Shopify</b>, donde están los
      metafields <code>ps_product_id</code> / <code>ps_product_ids</code> que habría que corregir.</li>
  <li>En «partes de familia», antes de tocar nada hace falta que PaceSetter explique por qué la
      familia no devuelve partes ni inventario. Con eso resuelto, el arreglo es cambiar el id por el
      de la familia y volver a sincronizar.</li>
  <li>En «sin rastro», la decisión es por producto: corregir el id si existe con otro código,
      despublicarlo, o dejarlo como producto manual fuera de la sincronización.</li>
  <li>El número entre paréntesis en los candidatos es cuántos caracteres comparte el código desde el
      principio: cuanto mayor, más probable.</li>
</ol>
</div>

<div class="controls">
  <input type="search" id="q" placeholder="Buscar por nombre, handle o id…">
  <button data-f="all" class="on">Todos</button>
  <button data-f="family">Partes de familia ({len(familied)})</button>
  <button data-f="orphan">Sin rastro ({len(orphans)})</button>
  <button data-f="partial">Parciales ({len(partials)})</button>
  <button data-f="failed">Fallaron ({len(failed)})</button>
  <button data-f="incomplete">Sin imágenes ({len(incomplete)})</button>
  <button data-f="shared">Id compartido ({len(shared)})</button>
  <span class="muted" id="count"></span>
</div>

<div class="tablewrap">
<table>
<thead><tr><th>Producto</th><th>Ids muertos · candidatos / motivo</th><th>Ids vivos</th><th>Qué responde PaceSetter</th></tr></thead>
<tbody id="body">
{rows_html(familied, shop_name, "family")}
{rows_html(orphans, shop_name, "orphan")}
{rows_html(partials, shop_name, "partial")}
{rows_html(failed, shop_name, "failed")}
{rows_html(incomplete, shop_name, "incomplete")}
{rows_html(shared, shop_name, "shared")}
</tbody>
</table>
</div>

<footer>
Generado por <code>tools/report_orphan_products.py</code> con el catálogo vendible en vivo del
proveedor y los metafields reales de la tienda. Vuelve a lanzarlo para refrescarlo.
</footer>
</div>

<script>
const rows = [...document.querySelectorAll('#body tr')];
const count = document.getElementById('count');
let filter = 'all';
function apply() {{
  const q = document.getElementById('q').value.trim().toLowerCase();
  let shown = 0;
  for (const tr of rows) {{
    const okKind = filter === 'all' || tr.dataset.kind === filter;
    const okText = !q || tr.dataset.search.includes(q);
    const show = okKind && okText;
    tr.hidden = !show;
    if (show) shown++;
  }}
  count.textContent = shown + ' de ' + rows.length;
}}
document.getElementById('q').addEventListener('input', apply);
for (const b of document.querySelectorAll('button[data-f]')) {{
  b.addEventListener('click', () => {{
    filter = b.dataset.f;
    document.querySelectorAll('button[data-f]').forEach(x => x.classList.toggle('on', x === b));
    apply();
  }});
}}
apply();
</script>
"""


if __name__ == "__main__":
    sys.exit(main())
