# Report: Grouping PaceSetter product variants

**Date:** July 22, 2026
**Subject:** Why it is not possible to automatically group products that are "variants"
(sizes) of the same product.
**Source:** live queries against PaceSetter's **PromoStandards Product Data 1.0.0** service
(`ProductDataService`, `getProduct` operation, production environment).

---

## 1. Executive summary

We were asked to group into a single listing those products that are actually the **same item in
different sizes** (e.g. Small / Medium / Large), which today appear as independent products.

**Conclusion:** automatic grouping is **not reliably possible**, because PaceSetter **does not
provide the data that indicates which products form a family**. The PromoStandards standard does
define a mechanism for this (the *Common Grouping* field), but **PaceSetter returns it empty for
every product verified**, including cases where the relationship is obvious.

Without that data, any grouping would be a **guess** based on the product code or name which — as
shown below — is **not reliable** and would produce incorrect groupings.

---

## 2. How grouping is supposed to work (per the standard)

PromoStandards Product Data defines, within each product's response (`getProduct`), an array of
**related products** (`RelatedProduct`). Each relation carries a type (`relationType`) that can be:

| relationType          | Meaning                                                         |
|-----------------------|----------------------------------------------------------------|
| `Substitute`          | Substitute product                                             |
| `Companion Sell`      | Companion / cross-sell product                                |
| **`Common Grouping`** | **Products that belong to the same family (e.g. sizes)**      |

The **`Common Grouping`** type is precisely the one a supplier is meant to use to say
*"these codes are the same product in different sizes."* If PaceSetter populated it, grouping would
be automatic and exact.

---

## 3. Finding: PaceSetter returns the field empty

When we query products that are **clearly size variants of the same item**, the related-products
field (`relatedProducts` / `RelatedProduct`) comes back **empty** in every case.

### Concrete examples (real data, 2026-07-22)

| Code    | Product name (PaceSetter)                                  | Related products |
|---------|------------------------------------------------------------|:----------------:|
| CBE109A | Gold Antique Finish Resin Cast Eagle **- Small**           | **empty**        |
| CBE109B | Gold Antique Finish Resin Cast Eagle **- Medium**          | **empty**        |
| CBE109C | Gold Antique Finish Resin Cast Eagle **- Large**           | **empty**        |
| C073A   | Black with Gold Florentine on Notched Corner Genuine Walnut | **empty**       |
| C073B   | Black with Gold Florentine on Notched Corner Genuine Walnut | **empty**       |
| C0611   | Large Blue Dynasty Award                                    | **empty**        |
| C0612   | Enterprise Large Blue Dynasty Award                         | **empty**        |
| C0613   | Small Blue Dynasty Award with Clear Lucite Base            | **empty**        |
| C0614   | Large Blue Dynasty Award with Clear Lucite Base           | **empty**        |

`CBE109A/B/C` is the clearest example: they are literally **Small / Medium / Large of the same
eagle**, and even so PaceSetter **declares no relationship between them**.

### Raw response (excerpt) from `getProduct` for `CBE109A`

```json
{
  "productId": "CBE109A",
  "productName": "Gold Antique Finish Resin Cast Eagle - Small",
  "relatedProducts": []
}
```

> `"relatedProducts": []` = **empty array**. There is no `Common Grouping` (nor any other type) that
> connects `CBE109A` to `CBE109B` or `CBE109C`.

---

## 4. Why a "best guess" cannot fill the gap

Since the supplier does not provide the data, the only alternative would be to **infer** families
from the code or the name. Both approaches are **unreliable** and would produce errors:

**a) By product code.** Some families follow a pattern (`CBE109` + A/B/C), but many do not. For
example, `C0611`, `C0612`, `C0613`, `C0614` share a numeric prefix, but they are **not clearly the
same product**: their names differ by more than the size ("Enterprise", "with Clear Lucite Base"…).
Grouping them automatically would be incorrect.

**b) By product name.** The names do not share a consistent root:

- `CBE109A/B/C` → they do share a base (`Gold Antique Finish Resin Cast Eagle`) with the size at the
  end.
- `C0611` = *Large Blue Dynasty Award* vs `C0612` = *Enterprise Large Blue Dynasty Award* → the name
  changes by more than the size.
- `C073A` and `C073B` → they have the **exact same name**, with no size token to distinguish them.

In other words, there is no single rule that works across the whole catalog (1,278 products) without
producing false positives (grouping different items) or false negatives (leaving real families
ungrouped). A guess-based grouping would **degrade data quality** rather than improve it.

---

## 5. Conclusion and recommendation

- Variant grouping **depends on data only the supplier can provide** (`Common Grouping` in
  PromoStandards).
- **PaceSetter does not provide it** (empty across the entire catalog verified), so it is **not
  technically possible to group automatically and reliably** with the available information.
- Code- or name-based alternatives are **not reliable** and are ruled out to avoid introducing
  errors into the catalog.

**Suggested path forward (requires supplier or business action):**

1. **Ask PaceSetter** to populate the `Common Grouping` field (`RelatedProduct`) in their Product
   Data service. This is the correct approach per the standard and would enable automatic, exact
   grouping with no further work on our side.
2. **Manual alternative:** if the supplier cannot do this, we could maintain a **manual mapping** of
   families (a curated list of "these codes = one product") maintained by the team. This is exact but
   requires ongoing maintenance as the catalog changes.

As soon as PaceSetter provides the data, the grouping feature is already built into the system and
would activate with no additional development.
