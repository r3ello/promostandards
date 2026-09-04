# PaceSetter — PromoStandards data request

Draft to send to PaceSetter's PromoStandards / IT contact. Findings measured on 2026-08-17 against
the live endpoints with our own account, sampling 20 product ids from `getProductSellable`
(1,278 products in the sellable catalog).

---

**Subject:** PromoStandards data gaps — decoration dimensions and Media `mediaType` filter

Hi,

We integrate with your PromoStandards services (Product Data 1.0.0, Media Content 1.1.0, Pricing &
Configuration 1.0.0, Inventory 1.2.1) to publish your catalog in our store. Three things in the feed
are blocking us. All are fields the specification already defines and your services already return —
they are just empty or unfiltered.

## 1. Decoration dimensions are always zero (Pricing & Configuration)

`getConfigurationAndPricing` returns a `LocationArray`, and we do get a location and a decoration
method. But every dimension comes back as `0`:

```
productId: C0611  (identical for C0501A, C0556, C0612, C0613, C1925, GI840, …)
  Location: "Back"  defaultLocation: true  maxDecoration: 1
    Decoration: "Laser Etch"
      decorationGeometry: "Square / Rectangle"
      decorationHeight:   0
      decorationWidth:    0
      decorationDiameter: 0
      decorationUom:      "Other"
```

**Sampled 20 products: 20 decorations, 0 of them carry any dimension.**

A concrete example. **GM668A — "Jade Glass Star Crescent"**
(<https://pacesetterawards.com/product-detail/Jade-Glass-Start-Crescent/1340>). Your own product
page advertises a "large engraving area" and offers a **Download Template** button, so the imprint
area is clearly known on your side. Through the API we get:

```
getProduct                 → name + description "5\" x 7\" x 1/4\" Jade Glass Star Crescent;
                             Sandblasting; Free Personal; Free Setup"   (product size, not imprint area)
getConfigurationAndPricing → Location "Back", Decoration "Sand Etch",
                             decorationHeight 0, decorationWidth 0, decorationDiameter 0, uom "Other"
getAvailableLocations      → location name only (no dimensions in the spec)
```

So an integrator can tell that the piece is sand-etched on the back, but not how large the etch may
be — the one number a buyer needs.

Could you populate `decorationHeight` / `decorationWidth` (and `decorationDiameter` for round areas)
with the actual imprint area, and set `decorationUom` to `Inches` instead of `Other`? Without them we
cannot show buyers how large an imprint can be — the information exists in your PDF spec sheets, but
not in the feed.

A related detail: `decorationGeometry` is specified as an enumeration of `Circle`, `Rectangular` or
`Other`. You currently send free text such as `"Square / Rectangle"` and `"Triangle"` (with trailing
whitespace padding), which does not match the enumeration and so cannot be parsed reliably.

## 2. The Media `mediaType` filter is ignored

`getMediaContent` takes a required `mediaType` of `Image | Video | Audio | Document`. Requesting
`Document` returns the same rows as `Image`:

```
GET media for GI840 with mediaType=Document
  → 9 rows, all mediaType="Image", classTypeId=107, all pointing at the same .jpg
```

**Sampled 20 products with `mediaType=Document`: 20 rows returned, 100% `Image`/`.jpg`, no document
of any kind.** Re-checked on GM668A specifically: all four media types (`Image`, `Document`,
`Video`, `Audio`) return the same single row — the product photo
`https://www.pacesetterawards.com/Images/ProductImages/Large/GM668A.jpg`, `classTypeId 107`.

The artwork template for that product *is* available on your website, but only behind an ASP.NET
postback (`__doPostBack('…$lnkDownloadTemplate','')`) — there is no stable URL we could reference,
and the only static PDFs on the page are the generic `art_specifications.pdf` and
`Creative_Questionnaire_Form.pdf`. Serving those templates through the Media service is the only
integration-friendly way for us to reach them.

Two requests:

- Please apply the `mediaType` filter as the specification requires, so `Document` returns documents.
- Please expose the **imprint template / spec-sheet PDFs** as `mediaType=Document`. Our customers
  already work from those PDFs, obtained outside PromoStandards; serving them through the Media
  service (ideally with the `DecorationArray` / `LocationArray` populated so each document is tied to
  its decoration location) would let us show them automatically per product.

## 3. Sellable catalog lists ids that Product Data does not have

`getProductSellable` returns ids for which `getProduct` then returns an empty response — for example
**GI840**, which is listed as sellable but has no product record (Inventory, Pricing and Media all
return data for it).

Should those ids be in the sellable list at all? If they are intentionally sellable, could
`getProduct` return their record too? We currently have to show such products without a name or
description.

---

Happy to provide request/response captures for any of the above.

Thanks,
Trophy Partner

---

## Notes for us (not part of the message)

- Item 3 is already handled on our side: the catalog degrades gracefully and flags the row
  "No product data" instead of failing (see `PromoStandardsNotFoundException`).
- The decoration-area UI is built and mapped end to end (`Configuration.locations` →
  `ProductDetail.decorationLocations`); it renders imprint areas to scale the moment dimensions
  arrive. Until then it falls back to a compact method list with a note.
- If PaceSetter will not populate the fields, the fallbacks are: (a) get the PDFs from wherever the
  customer gets them today and link them per product — needs one sample URL to check whether it is
  derivable from the product id; (b) parse the product's physical size from the media `description`
  (`6" x 9.5" x 2" Crystal rectangle …`), clearly labelled as product dimensions, not imprint area.
