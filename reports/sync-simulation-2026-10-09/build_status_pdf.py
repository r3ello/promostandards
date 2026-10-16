"""English, client-facing catalogue status report: TrophyPartner store vs PaceSetter (2026-10-09)."""
import csv, html, re, sys
from collections import Counter

SIM, OUT, DATE = sys.argv[1:4]
R = list(csv.DictReader(open(f"{SIM}/store_products.csv", encoding="utf-8-sig"), delimiter=";"))
U = list(csv.DictReader(open(f"{SIM}/unclaimed_sellable.csv", encoding="utf-8-sig"), delimiter=";"))
SYNCED_TODAY = {"EP2", "EP9", "EP10", "GI491A", "GI30A", "CD1050A", "CD1103A", "CD1022ACL", "CD959G", "CDUS02A"}


def e(s):
    return html.escape(s or "")


def kinds(r):
    return {k.split("=", 1)[1] for k in r["kinds"].split() if "=" in k}


def synced(r):
    return r["synced"] == "yes" or r["seed_id"] in SYNCED_TODAY


live = [r for r in R if r["status"] != "ARCHIVED"]
archived = len(R) - len(live)
created = sum(1 for r in R if re.match(r"p-(\d+)-", r["handle"]) and int(r["handle"].split("-")[1]) >= 10500)
done = [r for r in live if synced(r)]
pending = [r for r in live if not synced(r)]
lost = [r for r in done if r["verdict"] == "UNSYNCABLE"]
shared = [r for r in pending if r["verdict"] in ("OK", "MULTI_PRODUCT")]
parts = [r for r in pending if r["verdict"] == "UNSYNCABLE_PARTS"]
gone_all = [r for r in pending if r["verdict"] == "UNSYNCABLE"]
vanished = [r for r in gone_all if kinds(r) <= {"ORPHAN"}]
vanished_active = [r for r in vanished if r["status"] == "ACTIVE"]
vanished_draft = [r for r in vanished if r["status"] == "DRAFT"]
incomplete = [r for r in gone_all if not kinds(r) <= {"ORPHAN"}]
incomplete_all = incomplete + lost
uc = Counter(r["verdict"] for r in U)
sellable_no_data = sum(1 for r in csv.DictReader(open(f"{SIM}/supplier_ids.csv", encoding="utf-8-sig"), delimiter=";")
                       if r.get("kind") == "NO_PRODUCT_DATA")
assert len(shared) + len(parts) + len(gone_all) == len(pending)


def codes(r, limit=4):
    ids = r["ps_product_ids"].split()
    if len(ids) > limit:
        return f"{e(ids[0])} … {e(ids[-1])} ({len(ids)})"
    return " ".join(e(i) for i in ids)


def missing(r):
    k = kinds(r)
    out = []
    if "NO_PRODUCT_DATA" in k:
        out.append("product data")
    if "NO_PRICE" in k:
        out.append("prices")
    if "FAMILY_PART" in k:
        out.append("listed only under a family code")
    return ", ".join(out) or "—"


def table(rows, cols, widths):
    head = "".join(f"<th>{c}</th>" for c in cols)
    cg = "".join(f'<col style="width:{w}">' if w else "<col>" for w in widths)
    body = "".join("<tr>" + "".join(f"<td>{c}</td>" for c in row) + "</tr>" for row in rows)
    return f"<table><colgroup>{cg}</colgroup><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table>"


rows_vanished_a = [(i, e(r["title"]), f"<span class=m>{e(r['legacy_sku'])}</span>", f"<span class=m>{codes(r)}</span>")
                   for i, r in enumerate(sorted(vanished_active, key=lambda r: r["title"].lower()), 1)]
rows_vanished_d = [(i, e(r["title"]), f"<span class=m>{e(r['legacy_sku'])}</span>", f"<span class=m>{codes(r)}</span>")
                   for i, r in enumerate(sorted(vanished_draft, key=lambda r: r["title"].lower()), 1)]
rows_incomplete = [(i, e(r["title"]), "yes" if synced(r) else "no", f"<span class=m>{codes(r)}</span>", missing(r))
                   for i, r in enumerate(sorted(incomplete_all, key=lambda r: (not synced(r), r["title"].lower())), 1)]

doc = f"""<!doctype html><html lang=en><head><meta charset=utf-8>
<title>Catalogue status: store vs PaceSetter</title>
<style>
@page {{ size: A4; margin: 14mm 12mm; }}
body {{ font-family: 'Segoe UI', Arial, sans-serif; font-size: 9.5pt; color: #1a1a1a; }}
h1 {{ font-size: 19pt; margin: 0 0 1mm; }}
h2 {{ font-size: 13pt; margin: 8mm 0 2mm; border-bottom: 1.5px solid #333; padding-bottom: 1mm; page-break-after: avoid; }}
h3 {{ font-size: 10.5pt; margin: 5mm 0 1mm; page-break-after: avoid; }}
.sub {{ color: #555; margin-bottom: 5mm; }}
p, li {{ margin: 1.5mm 0; line-height: 1.45; }}
.box {{ background: #f3f6fa; padding: 3mm 4mm; border-left: 3px solid #2c6ecb; margin: 3mm 0; }}
.warn {{ background: #fff6e5; padding: 3mm 4mm; border-left: 3px solid #d98e04; margin: 3mm 0; }}
table {{ border-collapse: collapse; width: 100%; margin-top: 2mm; table-layout: fixed; font-size: 8.5pt; }}
td {{ overflow-wrap: anywhere; }}
th {{ background: #eceff3; text-align: left; font-size: 8pt; }}
th, td {{ border: 1px solid #d0d4da; padding: 1.1mm 1.6mm; vertical-align: top; }}
tr {{ page-break-inside: avoid; }}
thead {{ display: table-header-group; }}
.m {{ font-family: Consolas, monospace; font-size: 8pt; }}
.kpi {{ display: flex; gap: 3mm; margin: 3mm 0 4mm; }}
.kpi div {{ border: 1px solid #d0d4da; border-radius: 2mm; padding: 2mm 3mm; flex: 1; font-size: 8.5pt; }}
.kpi b {{ display: block; font-size: 16pt; }}
.sum td:last-child, .sum th:last-child {{ text-align: right; }}
.note {{ color: #555; font-size: 8pt; }}
.pb {{ page-break-before: always; }}
</style></head><body>
<h1>Catalogue status: store vs PaceSetter</h1>
<div class=sub>TrophyPartner Shopify store · PaceSetter catalogue · data read live from both on {DATE}</div>

<p>This report shows how many PaceSetter products in the store are already kept up to date automatically, what
is still pending and why, and what we need from you to move forward. <b>Principle:</b> PaceSetter is the source of
truth — the store follows what PaceSetter publishes, and every sync corrects the store to match it.</p>

<div class=kpi>
<div><b>{len(R):,}</b>PaceSetter products in the store ({len(live):,} active or draft, {archived} archived)</div>
<div><b>{len(done)}</b>synced and kept up to date automatically</div>
<div><b>{len(pending)}</b>not synced yet</div>
<div><b>{len(U)}</b>PaceSetter products not in the store</div>
</div>

<h2>1. What "synced" means</h2>
<p>A synced product shows PaceSetter's real variants (colours, sizes, years), its current price with our markup,
its stock, its quantity discounts and PaceSetter's photos. From then on stock and prices are refreshed
automatically, and any new variant PaceSetter adds is added to the store. Of the {len(done)} synced products,
{len(done) - created} came from the original migration and {created} were added as new products in September.</p>

<h3>Improvements made this week</h3>
<ul>
<li><b>Years-of-service awards</b> (e.g. Anniversary Achievement Plaque) now show a single <b>Year</b> choice
(1, 2, 3 … 50) instead of a confusing mix of colour and size names. Applied to the 5 such products.</li>
<li><b>Broken photos:</b> PaceSetter lists some image files its own website does not have. These are no longer
uploaded, so a product never shows a broken image.</li>
<li><b>The store follows PaceSetter when it removes things:</b> when PaceSetter stops listing a variant, the
next sync removes it from the store too — but only when PaceSetter has clearly answered without it. If PaceSetter
fails to answer, or answers incompletely, nothing is removed.</li>
<li><b>{len(SYNCED_TODAY)} more products synced today</b> (products that group several PaceSetter codes, such as
small/medium/large of the same award).</li>
</ul>

<h2>2. The {len(pending)} products not synced yet — and why</h2>
<table class=sum><colgroup><col style="width:42%"><col><col style="width:12%"></colgroup>
<thead><tr><th>Reason</th><th>What is needed</th><th>Products</th></tr></thead><tbody>
<tr><td><b>A. One PaceSetter product sold as several store listings</b>, which point to the same product or share
its variants (e.g. the Halley Plaque: 10 listings, all one PaceSetter product)</td>
<td>We merge them into one listing per PaceSetter product, exactly as PaceSetter has it — as we did with 44 of
them on October 6. Syncing them as they are would duplicate variants across listings.</td><td>{len(shared)}</td></tr>
<tr><td><b>B. Listings that point to a single colour or size code</b> instead of the PaceSetter product code</td>
<td>We point them to the product code and merge the listings that are the same PaceSetter product (see the
report <i>Products split across several store listings</i>).</td><td>{len(parts)}</td></tr>
<tr><td><b>C. PaceSetter no longer sells them</b>: none of their codes exists at PaceSetter today</td>
<td>Your review — see section 4 and the appendix.</td><td>{len(vanished)}</td></tr>
<tr><td><b>D. PaceSetter's data is incomplete</b>: still listed for sale, but without product data or prices</td>
<td>PaceSetter needs to complete the data — see section 5.</td><td>{len(incomplete)}</td></tr>
<tr><td><b>Total</b></td><td></td><td><b>{len(pending)}</b></td></tr>
</tbody></table>

<h2>3. PaceSetter products that are not in the store ({len(U)})</h2>
<p>PaceSetter offers {len(U)} product codes for sale that no store listing carries:</p>
<ul>
<li><b>{uc['NEW']} new products</b> — candidates to add to the store. We add new products only in batches you
have reviewed, as we did with the 132 added in September.</li>
<li><b>{uc['NOT_IMPORTABLE']} without price or product data</b> at PaceSetter — they cannot be published until
PaceSetter completes them.</li>
<li><b>{uc['DUPLICATE_RISK']} already in the store under another code</b> — handled with the merges of section 2.</li>
<li><b>{uc['ABSORBED']} will be added automatically</b> as variants of a store product the next time it syncs.</li>
</ul>

<h2>4. Products PaceSetter no longer sells ({len(vanished)})</h2>
<p>None of their PaceSetter codes appears in PaceSetter's catalogue any more: not in the list of products for sale
and with no product data. They were migrated from the previous store, have never been synced, and keep the price
and description they had on migration day. <b>{len(vanished_active)} are active</b> (customers can see and buy
them) and <b>{len(vanished_draft)} are already drafts</b> (hidden).</p>
<div class=box><b>Our proposal</b>
<ul>
<li>Move the <b>{len(vanished_active)} active ones to draft</b> — never delete them. A draft is hidden from customers
and can be republished with one click.</li>
<li>Before that, please review the list in the appendix: some may still be sold by PaceSetter <b>under a new
code</b>. For those, tell us the new code and we will point the listing to it instead.</li>
</ul></div>

<h2>5. Products where PaceSetter's data is incomplete ({len(incomplete_all)})</h2>
<p>These are still in PaceSetter's list of products for sale, but PaceSetter does not return their product data
(name, description, variant list) or their prices. {len(lost)} of them were synced earlier and lost their data at
PaceSetter since then — they keep showing what they had, but cannot be refreshed. Among the PaceSetter codes the
store uses, <b>{sellable_no_data} are for sale but have no product data today; on September 16 there was 1.</b></p>
<div class=warn><b>We recommend raising this list with PaceSetter.</b> We do not unpublish or change these
products: an incomplete answer from PaceSetter may be temporary, and the store must never lose a product because
PaceSetter failed to answer.</div>

<h2>6. What we need from you</h2>
<p><b>Merges (section 2, A and B) need nothing from you.</b> PaceSetter is the reference for the catalogue: where
the store sells one PaceSetter product as several listings, they become one listing with PaceSetter's variants.
The listings merged away are archived, never deleted, and redirect to the merged one, as on October 6.</p>
<ol>
<li><b>Discontinued products (section 4):</b> review the list; tell us any new codes; approve moving the rest to draft.</li>
<li><b>Incomplete data (section 5):</b> raise the list with PaceSetter.</li>
<li><b>New products (section 3):</b> tell us if you want a reviewed batch of the {uc['NEW']} new products.</li>
<li><b>Crystal Sports Tower</b> (11 sports): its codes mix two PaceSetter product lines (GM832 and GM806C). Please
confirm it should be one product before we sync it.</li>
</ol>
<p class=note>Counts as read from the store and from PaceSetter's PromoStandards services on {DATE}. "Products"
are store listings; a listing may cover several PaceSetter codes.</p>

<h2 class=pb>Appendix A — Active products PaceSetter no longer sells ({len(vanished_active)})</h2>
<p class=note>Proposed: move to draft. Mark any that PaceSetter sells under a new code.</p>
{table(rows_vanished_a, ["#", "Store listing", "Store SKU", "PaceSetter code(s)"], ["8mm", None, "15%", "32%"])}

<h2 class=pb>Appendix B — Draft products PaceSetter no longer sells ({len(vanished_draft)})</h2>
<p class=note>Already hidden from customers. No action needed unless one is sold under a new code.</p>
{table(rows_vanished_d, ["#", "Store listing", "Store SKU", "PaceSetter code(s)"], ["8mm", None, "15%", "32%"])}

<h2 class=pb>Appendix C — Products with incomplete data at PaceSetter ({len(incomplete_all)})</h2>
<p class=note>For PaceSetter. "Synced" = the product was synced before its data went missing.</p>
{table(rows_incomplete, ["#", "Store listing", "Synced", "PaceSetter code(s)", "Missing at PaceSetter"], ["8mm", None, "9%", "26%", "20%"])}
</body></html>"""

open(OUT, "w", encoding="utf-8").write(doc)
print("live", len(live), "done", len(done), "pending", len(pending), "shared", len(shared), "parts", len(parts),
      "vanished", len(vanished_active), len(vanished_draft), "incomplete", len(incomplete), "lost", len(lost), "U", len(U), uc,
      "nodata", sellable_no_data)
