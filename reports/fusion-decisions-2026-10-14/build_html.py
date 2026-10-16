"""Client decision sheet for the 37 store products that only a fusion or a manual fix can sync.

Reads store.json (the store's view of every product involved, fetched 2026-10-14) and writes
fusion-decisions-2026-10-14.html next to it. The groups and proposals are written out below by hand:
they come from simulate_store_sync.py (reports/sync-simulation-2026-10-14) plus PaceSetter's own
answers, and each one says what PaceSetter has, what the store has, and what we propose.

Usage: python reports/fusion-decisions-2026-10-14/build_html.py
"""
import html
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
store = json.loads((HERE / "store.json").read_text(encoding="utf-8"))

# section, key, title, PaceSetter facts, proposal, [(handle, role)], alternative, counts toward the 37
GROUPS = [
    ("ready", "G1", "Chisel Carve Tower family",
     "PaceSetter sells this family as <b>separate products by shape and size</b>, each with its colours: "
     "C701L <i>Chisel Carve Tower</i> (7 colours), C701BL <i>… on Base</i> (7), C609LB <i>Small … on Base</i> (4), "
     "C609LGO <i>Small Chisel Carve Tower</i> (Gold; the Blue, C609LBL, has stock but no product data or price), C610LB <i>Medium … on Base</i> (Blue, Gold). "
     "C610L <i>Medium Chisel Carve Tower</i> is already synced. "
     "The store sells it <b>one product per colour</b>, and two of them (Blue/Gold on Base) bundle several sizes.",
     "<b>One store product per PaceSetter product, colour as an option</b> (the sync cannot keep a "
     "one-product-per-colour layout: a PaceSetter code brings all of its colours):"
     "<ul><li><b>Chisel Carve Tower</b> (C701L, 7 colours) ← Blue, Gold, Green, Purple, Red Spectrum, Teal, and the "
     "tower from Black-on-Base</li>"
     "<li><b>Chisel Carve Tower on Base</b> (C701BL, 7 colours) ← Black, Green, Purple, Red, Teal on Base, and the "
     "C701BL colour of Blue/Gold on Base</li>"
     "<li><b>Small Chisel Carve Tower on Base</b> (C609LB, 4 colours) ← Gold on Base Large</li>"
     "<li><b>Small Chisel Carve Tower</b> (C609LGO) ← Gold Spectrum Trophy + Blue Spectrum Large; the Blue syncs once "
     "PaceSetter completes C609LBL (until then it takes the Gold's price)</li>"
     "<li><b>Medium Chisel Carve Tower on Base</b> (C610LB, Blue + Gold) ← Gold on Base (keeps its URL)</li></ul>"
     "Blue on Base keeps only the C612LB size (Blue + Gold), which waits for PaceSetter (no product data). "
     "<b>Photos:</b> PaceSetter sends one photo per product (the Teal one for C701L and C701BL, the Gold one for "
     "C609LB/C610LB); today's sync would replace each product's photos with that one. We propose keeping the "
     "store's own colour photos for this family. "
     "Merged products are archived, never deleted; the kept one keeps its URL and order history.",
     [("p-5478-blue-chisel-carve-tower-award", "→ C701L"), ("p-5882-gold-chisel-carve-tower-award", "→ C701L"),
      ("p-5909-green-chisel-carve-tower-award", "→ C701L"), ("p-8147-purple-chisel-carve-tower-award", "→ C701L"),
      ("p-6208-red-spectrum-chisel-carved-tower-award", "→ C701L"), ("p-8891-teal-chisel-carve-tower-award", "→ C701L"),
      ("p-5430-black-chisel-carve-tower-award-on-base", "→ C701BL (+ C701L black)"),
      ("p-5908-green-chisel-carve-tower-award-on-base", "→ C701BL"),
      ("p-6182-purple-chisel-carve-tower-award-on-base", "→ C701BL"),
      ("p-6196-red-chisel-carve-tower-award-on-base", "→ C701BL"),
      ("p-8890-teal-chisel-carve-tower-award-on-base", "→ C701BL"),
      ("p-8760-gold-chisel-carved-tower-on-base-large", "→ C609LB"),
      ("p-5898-gold-spectrum-chisel-carved-tower-trophy-award", "→ C609LGO"),
      ("p-8720-blue-spectrum-chisel-carved-tower-large", "→ C609LGO"),
      ("p-5479-blue-chisel-carved-tower-award-on-base", "context: keeps only C612LB (waits)"),
      ("p-5883-gold-chisel-carved-tower-on-base-award", "→ C610LB (gives C701BL/C612LB away)"),
      ("p-6082-medium-gold-chisel-carve-tower-award", "context: already synced (C610L)")],
     "One product per <b>shape</b> (Tower / Tower on Base) with Size + Colour. Possible, but PaceSetter gives "
     "no sizes for most of these codes, so variant names would be codes like “Blue (C701LBL)”. Not recommended.",
     14),
    ("ready", "G2", "Digi-Color Direct Plaque — Large and Extra-Large",
     "PaceSetter: CD547B <i>Large Digi-Color Direct Plaque</i> and CD547C <i>Extra-Large</i>, each in three finishes "
     "(Ebony, Mahogany, Walnut). The store has one product per size+finish.",
     "<ul><li><b>Large Digi-Color Direct Plaque</b> (CD547B, 3 finishes) ← Large Digi Large</li>"
     "<li><b>Extra-Large Digi-Color Direct Plaque</b> (CD547C, 3 finishes) ← Extra-Large Digi + Walnut Finish Digi</li></ul>"
     "Mahogany and Walnut products that also hold the Small/Medium size (CD547A) stay as they are: PaceSetter has "
     "no product data for CD547A yet.",
     [("p-8775-large-digi-large", "→ CD547B"), ("p-7985-extra-large-digi", "→ CD547C"),
      ("p-6322-walnut-finish-digi", "→ CD547C"),
      ("p-5696-digi-color-mahogany-plaque", "context: waits for CD547A"),
      ("p-6326-walnut-finish-digi", "context: waits for CD547A")],
     "One product for both sizes (Size + Finish).", 3),
    ("ready", "G3", "Polar Camel tumblers",
     "PaceSetter: CM712 <i>12 oz. Stemless</i> (18 colours), CM713 <i>16 oz. Stemless</i> (16), CM711 <i>20 oz. "
     "Ringneck</i> (19), CM710 <i>30 oz. Ringneck</i> (17). Minimum order 24.",
     "<ul><li><b>Polar Camel Stemless Tumblers</b>: keep the existing product with both sizes (12 oz + 16 oz, ~34 "
     "variants) and merge “Polar Camel Medium” (12 oz colours) and “Polar Camel Small” (16 oz colours) into it.</li>"
     "<li><b>Polar Camel Ringneck Tumblers</b>: only an id fix (CM710 → CM710*); it then syncs both sizes, ~36 "
     "variants. Nothing to merge.</li></ul>"
     "Note: today “Small” is the 16 oz and “Medium” the 12 oz, and their prices ($87.59 vs $40.95) do not match "
     "PaceSetter's order.",
     [("p-6356-polar-camel-stemless-wine-tumblers-personalized", "kept: CM712 + CM713"),
      ("p-8850-polar-camel-medium", "→ merged (12 oz)"), ("p-8848-polar-camel-small", "→ merged (16 oz)"),
      ("p-6168-polar-camel-ringneck-tumblers-personalized", "id fix only")],
     "Two separate products, 12 oz and 16 oz, and archive the combined one.", 4),
    ("ready", "G4", "Polar Camel beverage holders",
     "PaceSetter: CM727 <i>Powder Coated Insulated Can Holder, 12/16 oz</i> (16 colours) and CM737 <i>Slim "
     "Insulated Beverage Holder</i> (16 colours, <b>no price</b> in PaceSetter yet).",
     "<ul><li><b>Can Holder</b> = the “Large” product with all 16 CM727 colours (takes the black one from “Small”).</li>"
     "<li><b>Slim holder</b> = the “Small” product, CM737 only; it syncs once PaceSetter publishes a price.</li></ul>",
     [("p-8847-polar-camel-stainless-steel-beverage-holder-large", "kept: CM727"),
      ("p-8846-polar-camel-stainless-steel-beverage-holder-small", "becomes CM737 (waits for price)")],
     "", 2),
    ("price", "G5", "Cascade Carved Acrylic Award",
     "PaceSetter: CD1217 with Blue, Green, Red — product data yes, <b>price no</b>. The store's Green one carries "
     "an old code (CD1217GN, PaceSetter now says CD1217GR) and was set to draft as discontinued.",
     "Merge the three colours into one product now (Blue kept); it syncs when PaceSetter publishes the price. "
     "The Green draft comes back as a colour.",
     [("p-8714-blue-cascade-carved-acrylic-award", "kept"), ("p-8154-red-cascade-carved-acrylic-award", "→ merged"),
      ("p-8062-green-cascade-carved-acrylic-award", "→ merged (draft today)")],
     "Wait for PaceSetter's price before touching anything.", 2),
    ("price", "G6", "Black and Clear Acrylic Beveled Perpetual Plaque",
     "PaceSetter: CD1222 lists the plaque and its plate (CD1222PLT) as one product — <b>no price</b>.",
     "One product with the plate as a second variant (Plaque / Plate); syncs when PaceSetter publishes the price.",
     [("p-7872-black-and-clear-acrylic-beveled-perpetual-plaque", "kept"),
      ("p-7917-clear-acrylic-perpetual-plate", "→ merged")],
     "Keep the plate as its own product (it would then never sync: PaceSetter has no separate code for it).", 1),
    ("price", "G7", "Marble Florentine Plate",
     "PaceSetter: CD414 with 12 variants — 2 sizes × Blue/Green/Red × Ebony/Walnut board — <b>no price</b>.",
     "One product with the 12 variants (Blue on Ebony kept); syncs when PaceSetter publishes the price.",
     [("p-8718-blue-marble-florentine-plate-on-ebony-board", "kept"),
      ("p-7896-blue-marble-florentine-plate-on-ebony-finish-board", "→ merged"),
      ("p-8156-red-marble-florentine-plate-on-ebony-board", "→ merged"),
      ("p-8858-red-marble-florentine-plate-on-walnut-finish-board", "→ merged"),
      ("p-8766-green-marble-florentine-plate-on-ebony-board", "→ merged"),
      ("p-8767-green-marble-florentine-plate-on-walnut-finish-board", "→ merged"),
      ("p-5506-blue-marble-noble-plaque", "context: draft, discontinued code")],
     "Wait for PaceSetter's price before touching anything.", 6),
    ("other", "G8", "Freestanding Lucite Block",
     "PaceSetter: C0813A/B/C are three sizes, each a product of its own. CD549 and C0812 (other blocks the store "
     "listed here) have price and stock but <b>no product data</b>. One store product mixes all of them; a second "
     "one (already synced) has C0813C.",
     "Put the three C0813 sizes in the <b>already synced</b> product, and leave the mixed one holding only CD549 + "
     "C0812 until PaceSetter completes their data.",
     [("p-8754-freestanding-lucite-block", "kept: C0813A/B/C"),
      ("p-5753-freestanding-lucite-block", "keeps CD549 + C0812 (waits)"),
      ("p-6071-lucite-square-rectangular-block-awards", "context: CD549A, waits")],
     "", 1),
    ("other", "G9", "Crystal Column Sports Award with Ball",
     "PaceSetter: one product per sport (GM833A … GM833M, 12 live). GM833F no longer exists.",
     "Drop GM833F and sync as <b>one product with 12 sport variants</b>.",
     [("p-7951-crystal-column-sports-award-with-ball", "kept")],
     "", 1),
    ("other", "G10", "Crystal Sports Tower",
     "The product mixes the sports towers (GM832*) with the plain Crystal Tower (GM806C/D), which the store "
     "already sells as <i>Crystal Tower Award</i> (GM806A/B, synced).",
     "Move GM806C/D to <i>Crystal Tower Award</i> (it gets all 4 sizes); <i>Crystal Sports Tower</i> keeps the "
     "sports only.",
     [("p-7964-crystal-sports-tower", "keeps GM832 sports"),
      ("p-7965-crystal-tower-award", "takes GM806C/D")],
     "", 1),
    ("other", "G11", "Crystal Tablet with Subsurface Star Streams",
     "GI658 no longer exists in PaceSetter; GI657 and GI657B (<i>Crystal Tablet with Sub-Surface Window</i>) do.",
     "Drop GI658 and sync with GI657 + GI657B (we check the variants before publishing: PaceSetter's stock "
     "answer for GI657 also lists its Digi-Color version).",
     [("p-5657-crystal-tablet-with-subsurface-star-streams-award", "kept")],
     "Set to draft as discontinued, if the Star Streams design is not the Sub-Surface Window.", 1),
    ("other", "G12", "Windermere Plaque",
     "Of the four codes, only CD1093A (8×10) still exists. CD1093B, CD1094A, CD1094B are gone.",
     "Keep it with the one size PaceSetter still sells.",
     [("p-8226-windermere-plaque", "kept")],
     "Set to draft as discontinued.", 1),
]

SECTIONS = {
    "ready": ("Can be merged and synced now", "PaceSetter has everything: product data, prices and colours."),
    "price": ("Merge now, sync when PaceSetter publishes prices",
              "PaceSetter lists these products and their variants, but has no price for them yet."),
    "other": ("Single products to fix", "Each holds a dead code or a code that belongs elsewhere."),
}


def money(p):
    lo, hi = float(p["priceRangeV2"]["minVariantPrice"]["amount"]), float(p["priceRangeV2"]["maxVariantPrice"]["amount"])
    return f"${lo:,.2f}" if lo == hi else f"${lo:,.2f}–${hi:,.2f}"


def card(handle, role):
    p = store.get(handle)
    if not p:
        return f'<div class="prod missing">{html.escape(handle)}</div>'
    img = (((p.get("featuredMedia") or {}).get("preview") or {}).get("image") or {}).get("url")
    pid = p["id"].rsplit("/", 1)[1]
    ctx = role.startswith("context")
    status = "" if p["status"] == "ACTIVE" else f'<span class="badge">{p["status"].title()}</span>'
    return (f'<a class="prod{" ctx" if ctx else ""}" href="https://admin.shopify.com/store/trophy-partner/products/{pid}" '
            f'target="_blank" rel="noopener">'
            + (f'<img src="{html.escape(img)}&width=160" alt="" loading="lazy">' if img else '<div class="noimg"></div>')
            + f'<div><div class="t">{html.escape(p["title"])} {status}</div>'
            f'<div class="m">{html.escape((p.get("legacy") or {}).get("value") or "")} · {money(p)} · '
            f'{p["variantsCount"]["count"]} variant(s)</div>'
            f'<div class="m code">{html.escape(((p.get("ids") or {}).get("value") or "").replace(chr(34), "").strip("[]").replace(",", ", "))}</div>'
            f'<div class="role">{html.escape(role)}</div></div></a>')


parts = []
toc = []
for sec, (stitle, sdesc) in SECTIONS.items():
    groups = [g for g in GROUPS if g[0] == sec]
    n = sum(g[7] for g in groups)
    parts.append(f'<h2>{stitle} <span class="n">{len(groups)} decisions · {n} products</span></h2><p class="lead">{sdesc}</p>')
    for _, key, title, facts, proposal, prods, alt, count in groups:
        toc.append(f'<tr><td>{key}</td><td><a href="#{key}">{html.escape(title)}</a></td><td>{count}</td>'
                   f'<td class="pick" data-key="{key}">—</td></tr>')
        opts = [("approve", "Approve the proposal")]
        if alt:
            opts.append(("alt", "Alternative"))
        opts.append(("leave", "Leave as it is"))
        radios = "".join(f'<label><input type="radio" name="{key}" value="{v}"> {t}</label>' for v, t in opts)
        parts.append(
            f'<section class="group" id="{key}"><header><span class="key">{key}</span><h3>{html.escape(title)}</h3>'
            f'<span class="n">{count} product(s) of the 37</span></header>'
            f'<div class="cols"><div><h4>What PaceSetter has</h4><p>{facts}</p>'
            f'<h4>Proposal</h4><div class="proposal">{proposal}</div>'
            + (f'<h4>Alternative</h4><p>{alt}</p>' if alt else "")
            + f'</div><div class="prods"><h4>Store products involved</h4>{"".join(card(h, r) for h, r in prods)}</div></div>'
            f'<div class="decide"><div class="radios">{radios}</div>'
            f'<textarea name="{key}-note" placeholder="Notes (optional)"></textarea></div></section>')

page = f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Product Merge Decisions</title>
<style>
:root{{--bg:#f6f6f7;--card:#fff;--ink:#1f2124;--muted:#6d7175;--line:#e1e3e5;--accent:#2c6ecb;--ok:#008060;--warn:#b98900}}
@media (prefers-color-scheme:dark){{:root:not([data-theme=light]){{--bg:#151617;--card:#1f2124;--ink:#e3e5e7;--muted:#9ea3a8;--line:#34373b;--accent:#5b9bf0}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 -apple-system,Segoe UI,Roboto,sans-serif}}
main{{max-width:1100px;margin:0 auto;padding:32px 16px 64px}}
h1{{font-size:26px;margin:0 0 4px}}h2{{font-size:20px;margin:40px 0 4px}}h3{{font-size:17px;margin:0;flex:1}}
h4{{font-size:12px;text-transform:uppercase;letter-spacing:.05em;color:var(--muted);margin:14px 0 6px}}
.sub,.lead,.n{{color:var(--muted)}}.n{{font-size:13px;font-weight:400}}
.summary{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px 20px;margin:20px 0}}
.summary ul{{margin:6px 0;padding-left:20px}}
table{{border-collapse:collapse;width:100%;background:var(--card);border:1px solid var(--line);border-radius:10px;overflow:hidden}}
td,th{{padding:7px 12px;border-bottom:1px solid var(--line);text-align:left;font-size:14px}}th{{color:var(--muted);font-weight:600}}
a{{color:var(--accent)}}
.group{{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:18px 20px;margin:14px 0;break-inside:avoid}}
.group header{{display:flex;gap:10px;align-items:baseline}}
.key{{background:var(--accent);color:#fff;border-radius:6px;padding:1px 8px;font-size:13px;font-weight:600}}
.cols{{display:grid;grid-template-columns:1.1fr 1fr;gap:24px}}
@media (max-width:760px){{.cols{{grid-template-columns:1fr}}}}
.proposal{{border-left:3px solid var(--ok);padding:2px 0 2px 12px}}.proposal ul{{margin:4px 0;padding-left:18px}}
.prod{{display:flex;gap:10px;padding:8px;border:1px solid var(--line);border-radius:8px;margin-bottom:6px;text-decoration:none;color:inherit}}
.prod:hover{{border-color:var(--accent)}}.prod.ctx{{opacity:.6}}
.prod img,.noimg{{width:56px;height:56px;object-fit:contain;border-radius:6px;background:#fff;flex:none}}
.t{{font-weight:600;font-size:14px}}.m{{font-size:12px;color:var(--muted)}}.code{{font-family:Consolas,monospace;word-break:break-all}}
.role{{font-size:12px;font-weight:600;color:var(--ok)}}.ctx .role{{color:var(--muted)}}
.badge{{font-size:11px;background:#ffd79d;color:#4a3800;border-radius:4px;padding:0 5px;font-weight:500}}
.decide{{display:flex;gap:16px;flex-wrap:wrap;align-items:flex-start;border-top:1px solid var(--line);margin-top:14px;padding-top:12px}}
.radios label{{margin-right:16px;white-space:nowrap}}textarea{{flex:1;min-width:220px;min-height:38px;font:inherit;padding:6px;border:1px solid var(--line);border-radius:6px;background:var(--bg);color:var(--ink)}}
.pick{{font-weight:600}}
@media print{{body{{background:#fff}}.group{{border-color:#ccc}}.prod img{{width:44px;height:44px}}a{{color:inherit}}}}
</style></head><body><main>
<h1>Product merge decisions</h1>
<div class="sub">TrophyPartner × PaceSetter · October 14, 2026</div>
<div class="summary">
<b>37 store products</b> can only be synced with PaceSetter after a merge or a code fix — the rest of the catalogue is either
already synced (545), waiting on PaceSetter data (177), or discontinued and set to draft (213).
<ul>
<li><b>Why a merge:</b> the store often sells each colour or size as its own product, while PaceSetter sells one product
with those colours as variants. The sync can only follow PaceSetter's structure.</li>
<li><b>What a merge does:</b> one product is kept (its URL, reviews and order history stay); the others become its variants
and are <b>archived, never deleted</b>. Titles, descriptions and SEO of the kept product are not changed by the sync.</li>
<li><b>What we need:</b> one choice per group below. Choices are remembered in this browser and appear in the table.</li>
</ul></div>
<table><thead><tr><th></th><th>Decision</th><th>Products</th><th>Choice</th></tr></thead><tbody>{"".join(toc)}</tbody></table>
{"".join(parts)}
</main>
<script>
const KEY='fusion-decisions-2026-10-14';
let saved={{}};try{{saved=JSON.parse(localStorage.getItem(KEY)||'{{}}')}}catch(e){{}}
const label={{approve:'Approve',alt:'Alternative',leave:'Leave as is'}};
function paint(){{document.querySelectorAll('.pick').forEach(td=>{{const v=saved[td.dataset.key];td.textContent=v?label[v]:'—'}})}}
document.querySelectorAll('input[type=radio]').forEach(r=>{{if(saved[r.name]===r.value)r.checked=true;
 r.addEventListener('change',()=>{{saved[r.name]=r.value;try{{localStorage.setItem(KEY,JSON.stringify(saved))}}catch(e){{}}paint()}})}});
document.querySelectorAll('textarea').forEach(t=>{{if(saved[t.name])t.value=saved[t.name];
 t.addEventListener('input',()=>{{saved[t.name]=t.value;try{{localStorage.setItem(KEY,JSON.stringify(saved))}}catch(e){{}}}})}});
paint();
</script></body></html>"""
assert sum(g[7] for g in GROUPS) == 37, sum(g[7] for g in GROUPS)
(HERE / "fusion-decisions-2026-10-14.html").write_text(page, encoding="utf-8")
print("ok", len(GROUPS), "groups")
