---
name: sync-catalog
description: Run a PaceSetter → Shopify catalog sync (one product, the sellable set, or a resumed pass) and verify the result in the store. Use when asked to sync/import products, publish discounts or images, re-run a failed pass, or check what a product looks like in Shopify after syncing.
---

# Syncing the catalog

The app pushes supplier data into a live store. Everything here is written so a run can be started,
watched and reported without rediscovering how any of it works.

## The app is the user's to run. Wait for it.

**Do not start your own instance** (asked for explicitly, 2026-09-07). The user keeps one on
**8080**; when it is down or needs a restart, say so and wait. Two JVMs against one live store is
confusion nobody needs, and it cost a real incident: a `TaskStop` on `spring-boot:run` kills the
Maven wrapper but **leaves the JVM listening**, so a two-day-old survivor answered `/actuator/health`
as if it were fresh and a whole pass ran against stale code. If you ever must check what holds a
port: `Get-NetTCPConnection -LocalPort 8081 -State Listen` then `Stop-Process -Id <OwningProcess>`.

`spring-boot:run` compiles at startup, so **a code change is only live after a restart**. After
changing anything the sync touches, tell the user what to restart and why, then wait — and confirm on
one product before running a pass, by reading the field you changed back out of the store.

## Running a sync

One product, which is how every change should be tried first:

```
POST /api/sync/products/{supplierId}        # variants + stock + prices + discounts, one call
```
The endpoints need a session cookie: `POST /api/auth/login` with the credentials in
`src/main/resources/application-local.yaml` (`security.auth`). `tools/sync_store_catalog.py` has an
`opener(base)` helper that does the login and returns a urllib opener — import it rather than
rewriting the login.

The whole sellable catalog:

```
python tools/sync_store_catalog.py --only-sellable --base http://localhost:8080 \
    --timeout 600 --report <scratchpad>/sync-run-N.jsonl
```

* **Always `--only-sellable`.** The store holds ~298 tagged products but PaceSetter only serves ~81
  of them; without the flag the other 217 fail instantly with `getProduct returned no product` and
  the report is a wall of noise. See `informe-ids-huerfanos.html` for why they are dead.
* **Use a fresh report file per pass**, and `--skip-done <same file>` only to resume an interrupted
  one. A finished report skips everything, which looks like a no-op run.
* Run it with `run_in_background` (~15-25 min) and watch it with a Monitor whose filter is
  `done:|Traceback|FAILED` plus a couple of progress marks. **Do not** emit an event per product:
  monitors that talk too much get stopped.

## Verifying — always read the store back

The run's own summary says what the app believes. Confirm in Shopify with
`tools/check_discount_metafield.py` (`local_credentials`, `access_token`, `graphql` are importable)
and check the fields that matter for what changed:

| What | Where |
|---|---|
| SKU | variant `sku` = `<migration.legacy_sku>-<tail>` |
| Supplier identity | variant `trophy_sync.vendor_sku` = the part id |
| Colour / size | variant `trophy_sync.color`, `trophy_sync.size`, `custom.size` |
| Discounts | variant + product `trophy_discount.discount_tiers` |
| Images | product `media`, and each variant's own `media` |
| Bookkeeping | product `trophy_sync.vendor` / `source` / `last_sync_at` |

When paging tagged products, **inline the search term** — `products(query: "tag:promostandards")`.
Passing it as a GraphQL variable returns an empty page on a store holding hundreds; that false
negative once produced a completely wrong conclusion.

## Things that will bite

* **A supplier id that is not a product.** PaceSetter serves families under wildcard codes
  (`CM717*`) and some ids only exist as inventory rows. `getProduct` answering nothing is normal, not
  a bug; the sync is expected to skip them.
* **Images are asynchronous.** Shopify ingests media after the mutation returns and refuses to attach
  a non-`READY` one to a variant. `ForeignProductSync.readyMedia()` waits; that is why a product with
  photos takes ~30s instead of ~10s.
* **A supplier that answers nothing never causes a delete.** No media ⇒ images untouched; a null
  `onHand` ⇒ no inventory push. If you add a write path, keep that property.
* **Structural changes only happen on the explicit import/sync-product action**, never on the
  scheduled inventory/price jobs.

## Asking the supplier directly

When the app and the store disagree, ask PaceSetter itself rather than reasoning from the model:

```
python D:/ClaudeCode/toolbox/scripts/promostandards/query_service.py product \
    --endpoint <ProductDataService.svc> --product CM373BS --show colorName,hex
```
Credentials in `PROMOSTANDARDS_ID` / `PROMOSTANDARDS_PASSWORD` (they are in
`application-local.yaml`, under `promostandards.credentials`).

## Before touching new GraphQL

Any new document goes through `python tools/validate_shopify_graphql.py --store <shop>.myshopify.com`
(with `SHOPIFY_TOKEN`) — it runs each one against the live schema with ids that cannot exist, so
nothing is written. Add a case to its `CASES` table for the new constant, or it reports UNCHECKED.

## Reporting back

Give the counts that were verified in the store, not just the runner's. Name what failed and why —
supplier-side failures are the norm and the user needs to tell them apart from ours. If a pass
renumbers SKUs or changes variant titles, say so plainly: those are visible to anyone else reading
that store.
