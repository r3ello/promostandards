"""Third fusion batch (2026-10-09): the two groups the corrected plan.py found and PaceSetter confirms.

CM356* Leatherette Pen and CD120* Slide-in Certificate Ebony. Left out: CM295CK Cork Luggage Tag — no
PaceSetter answer lists it (CM295* is 12 colours, none cork), so it is a discontinued listing, not a merge.
The kept listing carries every code of its group, the parts too (CM356BB/BV, CD120M are rows of the
family's Inventory, not products), as the leftovers fix did: what no listing carries, nothing owns.

usage: python reports/fusion-3-2026-10-09/write.py reports/fusion-3-2026-10-09
"""
import csv, sys

D = sys.argv[1]
GROUPS = [
    dict(line="CM356*", name="Leatherette Pen", title="Leatherette Pen",
         keep=("p-8808-leatherette-pen-large", "Leatherette Pen Large", "CM356BL CM356BS CM356DB CM356GR CM356LB CM356PK CM356RS CM356RW CM356TL"),
         archive=[("p-8092-leatherette-pen", "Leatherette Pen", "CM356BB CM356BV")],
         variants=11),
    dict(line="CD120*", name='Slide-in Certificate Plaque - Ebony Finish for 11" x 8½" Insert',
         title="Slide-in Certificate Plaque - Ebony Finish",
         keep=("p-6267-slide-in-certificate-ebony-plaque-in-gift-box", "Slide-in Certificate Ebony Plaque in Gift Box", "CD120G"),
         archive=[("p-6266-slide-in-certificate-ebony-plaque-ebony-in-mailer-box",
                   "Slide-in Certificate Ebony Plaque Ebony in Mailer Box", "CD120M")],
         variants=2),
]
MF_ID = "Metafield: custom.ps_product_id [single_line_text_field]"
MF_IDS = "Metafield: custom.ps_product_ids [list.single_line_text_field]"
MF_INTO = "Metafield: trophy_sync.grouped_into [single_line_text_field]"
MF_GIDS = "Metafield: trophy_sync.grouped_ids [list.single_line_text_field]"
review, keep, archive, redirect, ids = [], [], [], [], []
for n, g in enumerate(GROUPS, 1):
    kh, kt, kids = g["keep"]
    all_ids = kids.split() + [i for _, _, a in g["archive"] for i in a.split()]
    review.append({"Group": f"G{n:02d}", "PaceSetter code": g["line"], "PaceSetter name": g["name"], "Role": "KEEP",
                   "Handle": kh, "Current title": kt, "New title": g["title"], "Current ps_product_ids": kids,
                   "Variants after merge": g["variants"]})
    keep.append({"Handle": kh, "Command": "MERGE", "Title": g["title"], MF_ID: all_ids[0], MF_IDS: ",".join(all_ids)})
    for h, t, a in g["archive"]:
        review.append({"Group": f"G{n:02d}", "PaceSetter code": g["line"], "PaceSetter name": g["name"],
                       "Role": "ARCHIVE", "Handle": h, "Current title": t, "New title": "",
                       "Current ps_product_ids": a, "Variants after merge": ""})
        archive.append({"Handle": h, "Command": "MERGE", "Status": "Archived", MF_ID: "", MF_IDS: "",
                        MF_INTO: kh, MF_GIDS: ",".join(a.split())})
        redirect.append({"Path": "/products/" + h, "Command": "MERGE", "Target": "/products/" + kh})
    ids.append(all_ids[0])


def w(name, rows):
    with open(f"{D}/{name}", "w", newline="", encoding="utf-8-sig" if name.startswith("0_") else "utf-8") as f:
        d = csv.DictWriter(f, fieldnames=list(rows[0]))
        d.writeheader()
        d.writerows(rows)


w("0_review.csv", review)
w("1_products-keep.csv", keep)
w("2_products-archive.csv", archive)
w("3_redirects.csv", redirect)
open(f"{D}/4_sync-ids.txt", "w").write("\n".join(ids) + "\n")
print(len(keep), "kept,", len(archive), "archived")
