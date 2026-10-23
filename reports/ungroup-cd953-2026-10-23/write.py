"""Write the Matrixify CSVs and the sync list that undo the CD953* states fusion, from snap.py's snapshot.

    python reports/ungroup-cd953-2026-10-23/write.py reports/ungroup-cd953-2026-10-23/before.json

Writes, next to this script:
  2_products-unarchive.csv  the 49 archived listings back: 39 Active, the 10 leftovers Draft, each with its own ps_product_id(s)
                            again and grouped_into / grouped_ids emptied (Matrixify deletes an empty one)
  3_redirects-delete.csv    the 52 redirects to p-7997 removed
  4_sync-ids.txt            CD953AK + the 49 ids, for tools/sync_store_catalog.py
"""

from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
SURVIVOR = "p-7997-frosted-acrylic-ak-state-cutout-on-black-plaque"
# The 10 leftovers of the fusion were drafts before it, and go back to being drafts (asked 2026-10-23).
LEFTOVERS = HERE.parents[0] / "fusion-2-2026-10-09" / "2_products-archive-leftovers.csv"


def main() -> int:
    snap = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
    with open(LEFTOVERS, encoding="utf-8-sig", newline="") as f:
        drafts = {r["Handle"] for r in csv.DictReader(f)}
    rows, ids = [], ["CD953AK"]
    for handle, p in snap["archived"].items():
        if p is None or (p.get("groupedInto") or {}).get("value") != SURVIVOR:
            print(f"refusing: {handle} is not an archived listing grouped into {SURVIVOR}")
            return 1
        own = json.loads(p["groupedIds"]["value"])
        rows.append([handle, "MERGE", "Draft" if handle in drafts else "Active", own[0], ",".join(own), "", "", "true", "true"])
        ids += own
    with open(HERE / "2_products-unarchive.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Handle", "Command", "Status",
                    "Metafield: custom.ps_product_id [single_line_text_field]",
                    "Metafield: custom.ps_product_ids [list.single_line_text_field]",
                    "Metafield: trophy_sync.grouped_into [single_line_text_field]",
                    "Metafield: trophy_sync.grouped_ids [list.single_line_text_field]",
                    "Metafield: trophy_sync.own_parts_only [boolean]",
                    "Metafield: trophy_sync.keep_store_images [boolean]"])
        w.writerows(rows)
    with open(HERE / "3_redirects-delete.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(["Path", "Command"])
        w.writerows([[f"/products/{r[0]}", "DELETE"] for r in rows])
    (HERE / "4_sync-ids.txt").write_text("\n".join(ids) + "\n", encoding="utf-8")
    print(f"{len(rows)} listings, {len(rows)} redirects, {len(ids)} ids to sync")
    return 0


if __name__ == "__main__":
    sys.exit(main())
