# Fusión de 44 productos repartidos — 2026-10-06

Cada grupo es **un** producto de PaceSetter que la tienda tiene partido en varios (uno por color o medida),
apuntando a ids de **variante** que PaceSetter no sirve como producto. Se queda uno (KEEP), pasa a apuntar
al código de producto y toma un título sin color ni medida; los demás se archivan con redirección.
Origen: grupo A de `split-products-2026-10-06.pdf` (45), menos Polar Camel (ver abajo).
Estado de los 127 productos comprobado en vivo el 2026-10-06: sin cambios desde el 16-09.

## Ficheros

| fichero | qué es |
|---|---|
| `0_review-fusion-44.csv` | **para revisar** (no se importa): grupo, KEEP/ARCHIVE, título actual y nuevo, variantes tras fusionar |
| `1_products-keep.csv` | Matrixify Products: 44 supervivientes — `Title` nuevo, `ps_product_id` = código de producto, `ps_product_ids` = todos los del grupo |
| `2_products-archive.csv` | Matrixify Products: 83 absorbidos — `Status = Archived`, `ps_product_id(s)` vacíos (Matrixify **borra** el metafield si la celda va vacía), y `trophy_sync.grouped_into` / `grouped_ids` como rastro (lo mismo que escribe la agrupación de la app) |
| `3_redirects.csv` | Matrixify Redirects: 83 redirecciones `/products/<absorbido>` → `/products/<superviviente>` |
| `4_sync-ids.txt` | los 44 códigos a sincronizar después |

## Orden

1. Revisar `0_review…` y corregir títulos o superviviente en `1_` / `2_` / `3_` (si cambias el superviviente
   de un grupo, cambia también su `grouped_into` en `2_` y su `Target` en `3_`).
2. Importar `1_products-keep.csv`, después `2_products-archive.csv`, después `3_redirects.csv`.
   Ese orden es el de la app: si se para a medias, nada queda sin dueño.
3. Esperar 5 min (índice en caché de la app) o reiniciarla, y sincronizar:
   `python tools/sync_store_catalog.py --only @reports/fusion-44-2026-10-06/4_sync-ids.txt --report reports/fusion-44-2026-10-06/run-sync.jsonl`

## A tener en cuenta

- **El handle no cambia**: algunos supervivientes conservan un color en la URL (`p-8699-7-piece-pink-…`). Cambiarlo
  es otra columna `Handle` + redirección; no incluido.
- Las redirecciones sólo actúan cuando la URL vieja da 404, es decir, una vez archivado el producto.
- Notas por grupo en la columna `Note`: CM746*/CM747* junta dos productos de PaceSetter (30 oz y 20 oz);
  CM372 tiene dos listados con el mismo título; `p-8088` (CM455) ya está en DRAFT; CD904AY* trae muchos más
  años de los 3 que tiene la tienda.
- **Fuera: Polar Camel** (CM711, CM712, CM713*, CM726*, CM731). Mezcla 5 productos de PaceSetter en
  `p-8848` / `p-8850`, y `p-6168` / `p-6356` ya llevan CM711 / CM712 / CM713. Requiere decisión aparte.
