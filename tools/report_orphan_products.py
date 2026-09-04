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

Reads the supplier's live sellable list through the running app (`/api/catalog/products`) and the
store through the Admin API. Writes nothing anywhere but the HTML file.

Requires: standard library only.
Origin: 2026-09-04, after the first full pass showed 217 unsyncable products.
"""

from __future__ import annotations

import argparse
import html
import json
import sys
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
      variants(first: 30) { nodes { sku } }
    }
  }
}
"""


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
                "skus": [v["sku"] for v in node["variants"]["nodes"] if v.get("sku")],
            })
        if not page["pageInfo"]["hasNextPage"]:
            return products
        cursor = page["pageInfo"]["endCursor"]


def family_of(dead_id: str, families: list[str]) -> str | None:
    """The wildcard family code covering this id, e.g. CM717BK -> CM717* (families are longest-first)."""
    return next((f for f in families if dead_id.upper().startswith(f)), None)


def family_detail(op, base: str, family: str, cache: dict) -> dict:
    """What the supplier actually serves under the family code - asked once per family."""
    if family in cache:
        return cache[family]
    import urllib.parse
    try:
        detail = json.load(op.open(f"{base}/api/catalog/products/"
                                   + urllib.parse.quote(family, safe=""), timeout=300))
        cache[family] = {"title": detail.get("title"), "variants": len(detail.get("variants") or []),
                         "pricing": len(detail.get("pricing") or []),
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8080")
    parser.add_argument("--out", default="informe-ids-huerfanos.html")
    parser.add_argument("--run", default="", help="a sync_store_catalog report, for the real errors")
    args = parser.parse_args()

    shop, client_id, client_secret, version = local_credentials()
    token = access_token(shop, client_id, client_secret)

    op = opener(args.base)
    catalog = json.load(op.open(f"{args.base}/api/catalog/products", timeout=600))
    sellable_list = sorted({row["productId"].strip().upper() for row in catalog if row.get("productId")})
    sellable = set(sellable_list)

    # What the supplier actually answered, where a sync tried it: better evidence than "not listed".
    errors = {}
    if args.run and Path(args.run).exists():
        for line in Path(args.run).read_text(encoding="utf-8").splitlines():
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if not row.get("ok") and row.get("error"):
                message = row["error"]
                if '"message":"' in message:
                    message = message.split('"message":"', 1)[1].split('"', 1)[0]
                errors[row["productId"].upper()] = message

    # Longest first: CD916A* must win over CD916* for CD916ABL.
    families = sorted({i[:-1] for i in sellable_list if i.endswith("*")}, key=len, reverse=True)

    products = store_products(shop, version, token)
    familied, orphans, partials, healthy = [], [], [], 0
    for product in products:
        dead = [i for i in product["ids"] if i.upper() not in sellable]
        live = [i for i in product["ids"] if i.upper() in sellable]
        product["dead"], product["live"] = dead, live
        product["families"] = {d: family_of(d, families) for d in dead}
        if not dead:
            healthy += 1
        elif live:
            partials.append(product)
        elif any(product["families"].values()):
            familied.append(product)
        else:
            orphans.append(product)

    cache = {}
    for product in familied + orphans + partials:
        product["suggestions"] = {d: candidates(d, sellable_list) for d in product["dead"]}
        product["answer"] = errors.get(product["canonical"].upper(), "")
        product["familyDetail"] = {f + "*": family_detail(op, args.base, f + "*", cache)
                                   for f in {v for v in product["families"].values() if v}}
    print(f"{len(cache)} familia(s) consultadas al proveedor", flush=True)

    shop_name = shop.split(".")[0]
    Path(args.out).write_text(
        render(products, familied, orphans, partials, healthy, sellable_list, shop_name),
        encoding="utf-8")
    print(f"{len(familied)} con familia · {len(orphans)} sin rastro · {len(partials)} parciales · "
          f"{healthy} sanos -> {args.out}")
    return 0


def rows_html(products: list[dict], shop_name: str, kind: str) -> str:
    out = []
    for p in sorted(products, key=lambda x: x["title"].lower()):
        numeric = p["gid"].rsplit("/", 1)[-1]
        admin = f"https://admin.shopify.com/store/{shop_name}/products/{numeric}"
        blocks = []
        for d in p["dead"]:
            fam = p["families"].get(d)
            if fam:
                info = p["familyDetail"].get(fam + "*", {})
                served = (f'título «{html.escape(str(info.get("title")))}», {info.get("pricing")} precios, '
                          f'{info.get("variants")} variantes, {info.get("images")} imagen(es)'
                          if "error" not in info else html.escape(info["error"]))
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


def render(products, familied, orphans, partials, healthy, sellable_list, shop_name) -> str:
    return f"""<!doctype html>
<html lang="es">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Ids PromoStandards sin correspondencia en PaceSetter</title>
<style>
:root {{
  --bg:#F3F5F7; --surface:#FFFFFF; --surface-2:#E9EDF1; --ink:#16202B; --muted:#58697A;
  --line:#D8E0E7; --accent:#1F5E6B; --accent-wash:#E3EEF0; --loss:#A32C2C; --loss-wash:#F8EAEA;
  --gain:#2F6B4F; --gain-wash:#E8F1EC; --brass:#8A6A22; --code-bg:#EDF1F4;
  --sans:"Segoe UI Variable Text","Segoe UI",system-ui,-apple-system,Arial,sans-serif;
  --mono:"Cascadia Mono",Consolas,ui-monospace,"SF Mono",monospace;
}}
@media (prefers-color-scheme: dark) {{ :root:not([data-theme="light"]) {{
  --bg:#0F161D; --surface:#16202A; --surface-2:#1C2833; --ink:#DFE7ED; --muted:#94A5B4;
  --line:#26343F; --accent:#6FB2BF; --accent-wash:#122730; --loss:#E08585; --loss-wash:#2A1718;
  --gain:#86C2A0; --gain-wash:#16241D; --brass:#C9A653; --code-bg:#0D1720;
}} }}
* {{ box-sizing:border-box; }}
body {{ margin:0; background:var(--bg); color:var(--ink); font-family:var(--sans); line-height:1.55; }}
.wrap {{ max-width:1180px; margin:0 auto; padding:2.5rem 1.5rem 5rem; }}
h1 {{ font-size:1.9rem; line-height:1.2; margin:0 0 .35rem; }}
h2 {{ font-size:1.15rem; margin:2.5rem 0 .75rem; }}
.sub {{ color:var(--muted); margin:0 0 2rem; }}
.card {{ background:var(--surface); border:1px solid var(--line); border-radius:12px; padding:1.25rem 1.4rem; }}
.card + .card {{ margin-top:1rem; }}
.stats {{ display:flex; flex-wrap:wrap; gap:.75rem; margin:1.5rem 0 2rem; }}
.stat {{ background:var(--surface); border:1px solid var(--line); border-radius:12px;
         padding:.85rem 1.1rem; min-width:9rem; }}
.stat b {{ display:block; font-size:1.6rem; line-height:1.1; }}
.stat span {{ color:var(--muted); font-size:.85rem; }}
.stat.bad b {{ color:var(--loss); }} .stat.warn b {{ color:var(--brass); }} .stat.good b {{ color:var(--gain); }}
p {{ margin:.6rem 0; }}
code {{ font-family:var(--mono); font-size:.85em; background:var(--code-bg); padding:.1rem .35rem; border-radius:4px; }}
code.dead {{ color:var(--loss); background:var(--loss-wash); }}
code.live {{ color:var(--gain); background:var(--gain-wash); }}
.controls {{ display:flex; flex-wrap:wrap; gap:.6rem; align-items:center; margin:1.25rem 0 .75rem;
             position:sticky; top:0; background:var(--bg); padding:.75rem 0; z-index:2; }}
input[type=search] {{ flex:1; min-width:14rem; padding:.6rem .8rem; border-radius:8px;
    border:1px solid var(--line); background:var(--surface); color:var(--ink); font:inherit; }}
button {{ padding:.55rem .9rem; border-radius:8px; border:1px solid var(--line);
          background:var(--surface); color:var(--ink); font:inherit; cursor:pointer; }}
button.on {{ background:var(--accent-wash); border-color:var(--accent); color:var(--accent); font-weight:600; }}
.tablewrap {{ overflow-x:auto; border:1px solid var(--line); border-radius:12px; background:var(--surface); }}
table {{ border-collapse:collapse; width:100%; font-size:.92rem; }}
th, td {{ text-align:left; padding:.7rem .9rem; border-bottom:1px solid var(--line); vertical-align:top; }}
th {{ position:sticky; top:0; background:var(--surface-2); font-size:.8rem; text-transform:uppercase;
      letter-spacing:.04em; color:var(--muted); }}
tr:last-child td {{ border-bottom:0; }}
td a {{ color:var(--accent); font-weight:600; text-decoration:none; }}
td a:hover {{ text-decoration:underline; }}
.handle {{ font-family:var(--mono); font-size:.78rem; color:var(--muted); }}
.meta {{ font-size:.78rem; color:var(--muted); }}
.idrow {{ margin-bottom:.35rem; }}
.sugg {{ display:block; font-size:.78rem; color:var(--muted); font-family:var(--mono); }}
.sugg.none {{ font-style:italic; font-family:var(--sans); }}
.sugg.fam {{ font-family:var(--sans); color:var(--accent); }}
.sugg.fam b {{ font-family:var(--mono); }}
.answer {{ font-size:.82rem; color:var(--muted); max-width:22rem; }}
.muted {{ color:var(--muted); }}
ol, ul {{ padding-left:1.2rem; }}
li {{ margin:.3rem 0; }}
footer {{ margin-top:3rem; color:var(--muted); font-size:.85rem; }}
</style>

<div class="wrap">
<h1>Ids PromoStandards sin correspondencia en PaceSetter</h1>
<p class="sub">Tienda {shop_name} · {date.today().isoformat()} · catálogo vendible del proveedor: {len(sellable_list)} ids</p>

<div class="stats">
  <div class="stat"><b>{len(products)}</b><span>productos con tag<br>promostandards</span></div>
  <div class="stat warn"><b>{len(familied)}</b><span>ids que son<br>partes de una familia</span></div>
  <div class="stat bad"><b>{len(orphans)}</b><span>sin rastro<br>en el proveedor</span></div>
  <div class="stat warn"><b>{len(partials)}</b><span>parciales<br>(algún id muerto)</span></div>
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
  <span class="muted" id="count"></span>
</div>

<div class="tablewrap">
<table>
<thead><tr><th>Producto</th><th>Ids muertos · candidatos</th><th>Ids vivos</th><th>Qué responde PaceSetter</th></tr></thead>
<tbody id="body">
{rows_html(familied, shop_name, "family")}
{rows_html(orphans, shop_name, "orphan")}
{rows_html(partials, shop_name, "partial")}
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
