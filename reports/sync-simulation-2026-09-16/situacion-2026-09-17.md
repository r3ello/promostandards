# Situación PaceSetter ↔ Shopify — 2026-09-17

Leído en vivo de la tienda `trophy-partner` (1169 productos) y cruzado con `plan-importacion.csv` (los 1022
productos PromoStandards de la migración del 10-09) y la simulación del 16-09.

## Productos de la tienda

| | productos |
|---|---|
| Migrados y sincronizados | 344 |
| Archivados al agruparlos en otro | 3 |
| **Sin tocar** | **680** |
| Nuevos creados en borrador (16-09) | 132 |
| Otros proveedores (resinas, 15-09) | 10 |

Los 680 sin tocar, por motivo (lista completa: `sin-tocar-2026-09-17.csv`):

| motivo | productos | qué hace falta |
|---|---|---|
| `NO_IMPORTABLE` | 286 | su id ya no existe en PaceSetter: archivar o buscar el código nuevo |
| `FUSION` | 240 | un producto PaceSetter repartido en varios de la tienda: decidir fusión (80 decisiones) |
| `ESPERA_CODIGO` | 132 | la unión de familias alcanzaría ids de otro producto (CD900*, CD953*…) |
| `LISTO` apartados | 17 | nombres de opción que salen mal en código |
| `SIN_IDENTIDAD_PS` | 5 | migrados **sin etiqueta ni `ps_product_id`**: la app no los ve (ver abajo) |

## Borradores nuevos que repiten un producto que ya estaba

La simulación sólo contaba como "en la tienda" los productos con id de PaceSetter. Estos seis no lo tenían
(cuatro migrados sin identidad y dos con un id que PaceSetter ya no sirve), así que sus productos salieron
como nuevos y se crearon otra vez:

| producto que ya estaba | por qué no se vio | borradores que lo repiten |
|---|---|---|
| `p-5859-full-color-certificate-printing` (PS6541, 3 variantes) | sin identidad PS | `p-10539`…`p-10543` (CERTPA, CERTPAA, CERTPB, CERTPC, CERTPD) |
| `p-6252-silver-steel-funnell` (PS6934) | sin identidad PS | `p-10568-silver-steel-funnel` (CMFLN) |
| `p-7873-black-and-silver-cloisonn-oval-date-bar` (PS8199) | sin identidad PS | `p-10631-…-cloisonne-oval-date-bar-…` (OVBARG) |
| `p-8841-optional-aluminum-achievement-bar-includes-free` (PS11114) | sin identidad PS | `p-10585-optional-aluminum-achievement-bar-…` (GBAR) |
| `p-8732-clear-acrylic-circle-on-clear-base-award` | ids muertos DCCD943A/B/C | `p-10531`, `p-10532`, `p-10533` (CD943A, CD943B, CD943C) |
| `p-6038-leatherette-rectangle-bottle-opener-keychain` | id muerto CM331 | `p-10548-leatherette-bottle-opener-keychain` (CM331*) |

Son **12 borradores sobre 6 productos**. El quinto sin identidad, `p-8125-optional-certificate-mounting-service`,
no tiene borrador.

Parecidos que **no** parecen duplicados (códigos distintos), para mirar si hay duda: `p-10586`/`p-10587`
(GI310BAR, GI310BARK) frente a `p-5498` (GI311BAR); `p-10565` (CM811*) frente a `p-8131` (CM810*).

## Productos de PaceSetter que no están en la tienda

| | ids |
|---|---|
| Nuevos sin inventario ("ProductID not found", comprobado en vivo el 16-09) | 143 |
| Nuevos con inventario incoherente (GI12, GI850, CD1076) | 3 |
| Con nombre o familia de un producto de la tienda (revisar a mano) | 73 |
| Entrarán como variantes al sincronizar su producto (CA21AXB/BXB/CXB, DCC3001DEB/EEB) | 5 |
| No importables (128 sin ficha, 19 sin precio) | 147 |
