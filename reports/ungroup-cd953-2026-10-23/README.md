# Deshacer la fusión de estados CD953* — 2026-10-23

Igual que `reports/ungroup-cd900-2026-10-23/` (leer su README: el primer intento allí es por qué existe
`trophy_sync.own_parts_only`). La fusión del 2026-10-09 juntó CD953* (*Frosted Acrylic State Cutout on
Black Plaque*, SKU base PS8630) en `p-7997-frosted-acrylic-ak-state-cutout-on-black-plaque` (51 variantes,
opción State) y archivó 49 listados con redirección.

**Foto en vivo antes** (`snap.py` → `before.json`): p-7997 ACTIVE, 51 variantes; 49 archivados con su
variante legacy y su foto; 49 URLs 301 → p-7997; ninguna orden desde el 09-10 en sus variantes.

## Decisiones (usuario, 2026-10-23)

- Los 39 que eran ACTIVE antes de la fusión → **Active**; los 10 sobrantes (CA, DE, FL, HI, ID, MA, MD,
  MI, NC, TN), que eran borradores → **Draft**.
- Todos llevan `own_parts_only` **y** `keep_store_images`: el SOAP Media de PaceSetter da la foto de
  Arizona para todos los estados, y cada listado conserva la suya.
- **USA (`CD953US`)** no tenía listado propio: se crea `p-10632-frosted-acrylic-usa-cutout-on-black-plaque`,
  **Draft**, sin foto. PaceSetter no tiene Product Data para CD953US (sí precio e inventario), así que **el
  sync no puede llevarlo**: precio ($90.99) y escalera de descuentos copiados de la variante que tenía,
  sin seguimiento de inventario. `trophy_sync.vendor` / `vendor_sku` puestos para que un pedido de él
  vaya a PaceSetter con su código.

## Orden

0. **Matrixify** `0_create-usa.csv` (crea USA).
1. `python reports/ungroup-cd953-2026-10-23/1_alaska.py` (muestra) y `--apply`: borra las 50 variantes de
   otros estados (queda `PS8630-AK`), State → Title, `ps_product_ids` = `[CD953AK]`, los dos flags, título.
2. **Matrixify** `2_products-unarchive.csv` (39 Active + 10 Draft, con sus ids y los dos flags).
3. **Matrixify** `3_redirects-delete.csv` (49).
4. App reiniciada (o 5 min), sincronizar primero CD953AL, revisar, luego `4_sync-ids.txt`.
5. `snap.py` → `after.json` y comprobar.

## Resultado (2026-10-23)

`after.json`: 50 listados (Alaska + 49) + USA, una variante cada uno, `Title / Default Title`, **todas las
fotos intactas** (mismo id de media que en `before.json`), ninguno agotado, ninguna redirección.
- **40 sincronizados y Active** (Alaska + los 39): SKU legacy (`PS8631`…), `vendor_sku`, stock en la location
  de prod, escalera de descuentos.
- **Los 10 Draft no se sincronizan**: PaceSetter no tiene Product Data para ninguno (CA, DE, FL, HI, ID, MA,
  MD, MI, NC, TN — por eso eran borradores antes de la fusión). Quedan con su variante legacy, sin
  `vendor_sku`. Como USA, tienen precio e inventario en PaceSetter: los llevaba el producto fusionado vía
  la familia.
