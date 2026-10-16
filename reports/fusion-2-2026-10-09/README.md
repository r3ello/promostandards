# Fusión, 2º lote — 2026-10-09

Listados de la tienda que son **un** producto de PaceSetter (o los mismos códigos repartidos entre varios
listados). Regla: la tienda queda como PaceSetter — un listado con sus variantes; los demás se archivan
(nunca se borran) con redirección. Mismo procedimiento y formato que `reports/fusion-44-2026-10-06/`.
Origen: simulación fresca `reports/sync-simulation-2026-10-09/` (`plan.py` agrupa por línea de PaceSetter,
`write.py` escribe los CSV). **Comprobado en vivo el 2026-10-09** (`verify_live.py`): los 127 listados siguen
con el estado, los `ps_product_ids` y la única variante legacy que dice la simulación.

## Dos tandas

| tanda | grupos | se quedan | se archivan | ficheros |
|---|---|---|---|---|
| principal | 16 | 16 | 19 | `0_review.csv`, `1_products-keep.csv`, `2_products-archive.csv`, `3_redirects.csv`, `4_sync-ids.txt` |
| estados | 2 | 2 | 90 | los mismos con sufijo `-states` |

**Estados** va aparte porque cambia mucho la tienda: `CD900*` (52 listados, uno por estado + óvalo y
estrella) y `CD953*` (40 estados) pasan a **un producto cada uno** con el estado como variante (53 y 51
variantes, por debajo del límite de 100). Es como lo tiene PaceSetter, pero se pierden 90 URLs propias por
estado (quedan redirigidas). El superviviente conserva su handle (`p-5805-alaska-…`, `p-7997-…-ak-…`):
conviene renombrarlo (columna `Handle` + redirección), no incluido.

## Orden (por tanda)

1. Revisar `0_review*.csv` (títulos nuevos y quién se queda). Si cambias el superviviente, cambia su
   `grouped_into` en `2_` y su `Target` en `3_`.
2. Importar con Matrixify `1_…`, luego `2_…`, luego `3_…`.
3. Esperar 5 min o reiniciar la app, y sincronizar:
   `python tools/sync_store_catalog.py --only @reports/fusion-2-2026-10-09/4_sync-ids.txt --report reports/fusion-2-2026-10-09/run-sync.jsonl`

## Notas

- `CM286*` / `CM289*`: PaceSetter llama "Cork …" al de corcho, pero es la misma familia.
- `CA21CX*`: `p-5900` dice "small" y es el dorado grande (CA21CXG). Las estrellas quedan en 2 listados
  (pequeña+mediana / grande), como ya las agrupaba la tienda.
- `DCC3001C*`: tres medidas (C/D/E) en nogal y ébano en un listado.
- Códigos que PaceSetter ya no sirve (p. ej. `CM289WM`, `CM415WM`) no pasan al superviviente; quedan en
  `grouped_ids` del archivado.

## Fuera de este lote (necesitan decisión: un listado mezcla varios productos de PaceSetter)

- `C0812` / `C0813` / `CD549` (2 listados): uno mezcla los tres; sólo C0813A/B tienen datos.
- Torres Chisel Carve (16 listados): mezclan C609LB, C610LB, C612LB, C701L y C701BL — hay que repartir por producto.
- `CD547*` (6 listados): mezclan medidas A/B/C y CD547A no tiene datos; fusionar la quitaría.
- `CM727*` (2): Polar Camel, mezcla CM727 y CM737 (sin datos).
- `DCCD520*` (4): se repuntarían a DCC3001BEB; DCCD520 no tiene datos.
- 26 grupos más sin ficha o sin precio en PaceSetter (no fusionables hasta que respondan).

## Complemento: 11 listados que se quedaron fuera (`-leftovers`)

`plan.py` no los agrupó (corregido después): `p-7116` "Frosted Lucite USA Cutout" (ACTIVO, `CD900USA`, sin
ficha propia en PaceSetter) y 10 borradores de CD953 (CA, DE, FL, HI, ID, MA, MD, MI, NC, TN), cuyo código es
parte de varios. Sus códigos ya son variantes de los supervivientes. `leftovers.py` los encuentra en vivo;
`write_leftovers.py` escribe:
- `1_products-keep-leftovers.csv`: añade esos códigos a `ps_product_ids` de `p-5805` y `p-7997` (sólo esa columna).
- `2_products-archive-leftovers.csv`: archiva los 11, con `grouped_into` / `grouped_ids`.
- `3_redirects-leftovers.csv`: 11 redirecciones.
Cargar en ese orden y sincronizar otra vez `4_sync-ids-states.txt`.
