# Fusión, 3er lote — 2026-10-09

Dos grupos que el `plan.py` corregido encontró y que PaceSetter confirma como un solo producto. Mismo formato
que `reports/fusion-2-2026-10-09/`. Estado en vivo comprobado el 09-10: los 4 listados activos, una variante
legacy cada uno.

| grupo | se queda | se archiva | variantes tras sincronizar |
|---|---|---|---|
| CM356* Leatherette Pen | `p-8808-leatherette-pen-large` → "Leatherette Pen" | `p-8092-leatherette-pen` (BB, BV) | 11 |
| CD120* Slide-in Certificate Ebony | `p-6267-…-in-gift-box` → "Slide-in Certificate Plaque - Ebony Finish" | `p-6266-…-in-mailer-box` (CD120M) | 2 |

El superviviente lleva todos los códigos del grupo, partes incluidas (CM356BB/BV y CD120M son filas del
Inventory de la familia, no productos), como en el complemento `-leftovers`.

**Fuera:** `p-5621` Cork Luggage Tag (`CM295CK`). Ninguna respuesta de PaceSetter lo lista: CM295* son 12
colores, ninguno de corcho. No es una fusión sino un producto que PaceSetter ya no vende (grupo C del informe).

## Orden
1. Revisar `0_review.csv`.
2. Matrixify: `1_products-keep.csv`, `2_products-archive.csv`, `3_redirects.csv`.
3. Sincronizar `4_sync-ids.txt` y correr `../fusion-2-2026-10-09/leftovers.py` para comprobar que no quede nada suelto.
