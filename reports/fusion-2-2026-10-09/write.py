"""Write the Matrixify CSVs of the second fusion batch from plan.json (see plan.py).

Same shape as reports/fusion-44-2026-10-06: 1_ keep, 2_ archive, 3_ redirects, 4_ sync ids, 0_ review.
The two state-cutout families (CD900*, CD953*: 92 state listings -> 2) go in their own files with the
suffix -states, so they can be loaded, or held back, on their own.

usage: python write.py <dir>
"""
import csv, json, sys

D = sys.argv[1]
P = json.load(open(f"{D}/plan.json", encoding="utf-8"))

# Listings that mix several PaceSetter products, or point at codes with no data: not a plain merge.
HOLD = {
    "C0812": "one listing mixes C0812, C0813 and CD549; only C0813A/B have data",
    "C609LB": "16 listings mix five tower products (C609LB, C610LB, C612LB, C701L, C701BL)",
    "CD547A": "listings mix sizes A/B/C; CD547A has no data, merging would drop it",
    "CM727*": "Polar Camel; mixes CM727 and CM737 (no data)",
    "DCC3001BEB": "the DCCD520 listings would be repointed to DCC3001BEB; DCCD520 has no data",
}
TITLE = {
    "C1407L*": "Diamond Carved Octagon Plaque",
    "C6801*": "Hi-Tech Lucite Riser Plaque with Wood Backing and Plate",
    "CA21AX*": "Sculptured Lucite Star Award",
    "CA21CX*": "Sculptured Lucite Star Award - Large",
    "CD121*": "Slide-in Certificate Plaque - Rosewood Piano Finish",
    "CD900*": "Frosted Lucite Cutout on Risers Award with Choice of State or Shape",
    "CD953*": "Frosted Acrylic State Cutout on Black Plaque",
    "CM286*": "Leatherette Flask",
    "CM288*": "Leatherette Wine Bag",
    "CM289*": "Leatherette Beverage Holder",
    "CM415*": "Leatherette Journal",
    "CM721*": "Journal with Phone Pouch",
    "CM722A": "Leatherette Picture Frame",
    "DCC3001C*": "Choice of Digi-Color Plate on Economy Board",
    "DCCD424**": "Digi-Color Lucite Award",
    "GI583M": "Carved Clear Crystal on Black Base with Choice of Medallion",
    "GI586*": "Two-Tone Crescent Curved Glass",
    "GM809A*": "Clear Center Crystal with Color Accent",
}
NOTE = {
    "CD900*": "52 state listings become one product with a State option; the kept handle names Alaska",
    "CD953*": "40 state listings become one product with a State option; the kept handle names AK",
    "CM286*": "PaceSetter names the cork one 'Cork Flask'; same family",
    "CM289*": "PaceSetter names the cork one 'Cork Beverage Holder'; same family",
    "CA21CX*": "p-5900 says 'small' but is the gold large (CA21CXG)",
    "DCC3001C*": "three sizes (C/D/E) in walnut and ebony",
}
STATES = {"CD900*", "CD953*"}
SURVIVOR = {}  # line -> handle, to override plan.py's choice

MF_ID = "Metafield: custom.ps_product_id [single_line_text_field]"
MF_IDS = "Metafield: custom.ps_product_ids [list.single_line_text_field]"
MF_INTO = "Metafield: trophy_sync.grouped_into [single_line_text_field]"
MF_GIDS = "Metafield: trophy_sync.grouped_ids [list.single_line_text_field]"

out = {k: {"keep": [], "archive": [], "redirect": [], "ids": [], "review": []} for k in ("", "-states")}
held = []
g = 0
for p in P:
    key = p["lines"][0]
    if key in HOLD:
        held.append((key, len(p["store"]), HOLD[key]))
        continue
    title = TITLE[key]
    g += 1
    o = out["-states" if key in STATES else ""]
    survivor = SURVIVOR.get(key, p["survivor"])
    variants = max(int(s["expected_variants"] or 0) for s in p["store"])
    for s in p["store"]:
        keep = s["handle"] == survivor
        o["review"].append({"Group": f"G{g:02d}", "PaceSetter code": " ".join(p["lines"]),
                            "PaceSetter name": " / ".join(p["names"]), "Role": "KEEP" if keep else "ARCHIVE",
                            "Handle": s["handle"], "Status": s["status"], "Current title": s["title"],
                            "New title": title if keep else "", "Legacy SKU": s["legacy_sku"],
                            "Current ps_product_ids": s["ps_product_ids"],
                            "Variants after merge": variants if keep else "",
                            "Note": NOTE.get(key, "") if keep else ""})
        if keep:
            o["keep"].append({"Handle": s["handle"], "Command": "MERGE", "Title": title,
                              MF_ID: p["ids"][0], MF_IDS: ",".join(p["ids"])})
        else:
            o["archive"].append({"Handle": s["handle"], "Command": "MERGE", "Status": "Archived", MF_ID: "",
                                 MF_IDS: "", MF_INTO: survivor, MF_GIDS: ",".join(s["ps_product_ids"].split())})
            o["redirect"].append({"Path": "/products/" + s["handle"], "Command": "MERGE",
                                  "Target": "/products/" + survivor})
    o["ids"].append(p["ids"][0])


def w(name, rows):
    with open(f"{D}/{name}", "w", newline="", encoding="utf-8-sig" if name.startswith("0_") else "utf-8") as f:
        d = csv.DictWriter(f, fieldnames=list(rows[0]))
        d.writeheader()
        d.writerows(rows)


for sfx, o in out.items():
    w(f"0_review{sfx}.csv", o["review"])
    w(f"1_products-keep{sfx}.csv", o["keep"])
    w(f"2_products-archive{sfx}.csv", o["archive"])
    w(f"3_redirects{sfx}.csv", o["redirect"])
    open(f"{D}/4_sync-ids{sfx}.txt", "w").write("\n".join(o["ids"]) + "\n")
    print(sfx or "main", "groups", len(o["keep"]), "archived", len(o["archive"]))
json.dump(held, open(f"{D}/held.json", "w"), indent=1)
print("held", held)
