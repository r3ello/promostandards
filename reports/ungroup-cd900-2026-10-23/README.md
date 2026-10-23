# Deshacer la fusión de estados CD900* — 2026-10-23

La fusión del 2026-10-09 (`reports/fusion-2-2026-10-09/`, tanda `-states` + `-leftovers`) juntó los 53
listados de CD900* (un estado cada uno + óvalo, estrella y USA) en `p-5805-alaska-state-silhouette-awards`
(SKU base PS6487), con opción State, y archivó los otros 52 con redirección 301. Esto lo deshace: cada
estado vuelve a ser su propio producto, **Active**, como estaba antes. CD953* (PS8630) no se toca.

**Foto en vivo antes de empezar** (`snap.py` → `before.json`, 2026-10-23, trophy-partner):
p-5805 ACTIVE con 53 variantes (opción State, `ps_product_ids` 53); los 52 archivados ARCHIVED, 1 variante
legacy cada uno (`PS6488`…, Default Title, sin `vendor_sku`), sin `ps_product_id`, con `grouped_into`;
sus 52 URLs responden 301 → p-5805; **ninguna orden** desde el 09-10 compró una de las variantes que se borran.

## Orden

1. **Alaska** (script, escribe por API):
   `python reports/ungroup-cd900-2026-10-23/1_alaska.py` (muestra) y luego `--apply`.
   Borra las 52 variantes de otros estados (queda `PS6487-AK`, mismo id), State → `Title / Default Title`,
   `ps_product_ids` = `[CD900AK]`, título "Alaska State Silhouette Awards". Va **primero**: así ningún
   código de PaceSetter está en dos productos a la vez.
2. **Matrixify** `2_products-unarchive.csv`: los 52 a Active con su `ps_product_id(s)`; `grouped_into` /
   `grouped_ids` vacíos (Matrixify los borra).
3. **Matrixify** `3_redirects-delete.csv`: borra las 52 redirecciones. (Un producto activo ya gana a su
   redirección — Shopify sólo redirige una URL que da 404 —, pero quedarían como basura.)
4a. **Matrixify** `5_own-parts-only.csv`: `trophy_sync.own_parts_only = true` en los 53. **Imprescindible**:
   PaceSetter contesta el Inventory de CD900IL con las 53 partes de la familia, y sin el flag cada sync
   mete los 53 estados en cada producto (lo que hizo el primer intento, abajo). Necesita la app con el
   código del 2026-10-23 (`ForeignProductSync.ownPartsOnly`).
4. Esperar 5 min o reiniciar la app (índice de la tienda en caché), con la app en :8080, y sincronizar:
   `python tools/sync_store_catalog.py --only @reports/ungroup-cd900-2026-10-23/4_sync-ids.txt --report reports/ungroup-cd900-2026-10-23/run-sync.jsonl`
   Cada estado adopta su variante legacy: SKU, precio, stock, foto y escalera de descuentos.
5. Verificar: `python reports/ungroup-cd900-2026-10-23/snap.py reports/ungroup-cd900-2026-10-23/after.json`.

## Primer intento de sync (2026-10-23, fallido)

Sin el flag, y con `application-local.yaml` apuntando a la tienda de producción pero con el
`location-id` de dev (`74343743582`, borrada): 22 de los 53 se sincronizaron a medias (State de un
valor, SKU `PS65xx-XX`, `vendor_sku`) y su `productVariantsBulkCreate` de las otras 52 partes falló
por la location — eso evitó el daño. Pero quedaron `tracked` con 0 en stock: **agotados**. Reparado
con `untrack.py --apply` (tracked=false, como estaban). Log: `run-sync.log` / `run-sync.jsonl`.

## A tener en cuenta

- `p-7116` USA (`CD900USA`) no tiene ficha en PaceSetter: su sync falla y queda como antes de la fusión
  (activo, su variante legacy).
- Las fotos de cada estado salen del SOAP Media de PaceSetter en el sync (`replace-existing`).
- Regenerar los CSV desde una foto nueva: `python reports/ungroup-cd900-2026-10-23/write.py <snap.json>`.
