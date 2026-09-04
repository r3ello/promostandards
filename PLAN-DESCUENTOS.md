# Descuentos por cantidad — JSON en un metafield

Estado: **implementado** (2026-09-03). Sustituye por completo a la integración con la app Orichi
(*OC Quantity Breaks & Limit*), que queda **eliminada del código**: `PLAN-DESCUENTOS-ORICHI.md`,
`OrichiClient`/`OrichiTokenStore`/`OrichiCampaignMapper` y sus tests ya no existen.

## 1. Qué se publica

Una variante de Shopify lleva **un** precio — el del primer tramo —, así que cada tramo más barato
tiene que publicarse en otro sitio que el escaparate sepa leer. Ese sitio es **un metafield con toda
la escalera en JSON**. Ejemplo real del cliente (CM373BS), para la tabla
`25 → $19.99 · 75 → $18.99 · 150 → $15.99`:

```json
{"currencyCode":"USD","tiers":[{"minQuantity":25,"discountAmount":0},
                               {"minQuantity":75,"discountAmount":1},
                               {"minQuantity":150,"discountAmount":4}]}
```

- **`discountAmount` es lo que se descuenta por unidad** a partir de esa cantidad, no el precio
  (corregido con el cliente el 2026-09-03; el primer ejemplo que dio traía los precios de venta ahí).
- El primer tramo es la base: `0` de descuento = vale el precio de la variante. Su `minQuantity` es el
  primer tramo del proveedor: 25 arriba, 1 en la mayoría, 4 en una familia que sólo se vende en packs
  (CM297).
- Importes con dos decimales como mucho y sin ceros de relleno (`14`, `19.99`), como los escribe el
  payload real de la tienda.
- Un tramo que no es más barato que la base no es un descuento y no se publica.

Ventaja sobre Orichi: no hay API de terceros, ni token de 24 h, ni campañas que reconciliar.
Publicar es **una escritura idempotente** — se puede repetir sin efectos.

## 2. Dónde se publica (configurable)

```yaml
discounts:
  enabled: ${DISCOUNTS_ENABLED:true}
  namespace: "${DISCOUNTS_METAFIELD_NAMESPACE:trophy_discount}"
  key: "${DISCOUNTS_METAFIELD_KEY:discount_tiers}"
  type: "${DISCOUNTS_METAFIELD_TYPE:json}"
```

El nombre definitivo (2026-09-03): **`trophy_discount.discount_tiers`**. Es lo único que hay que
tocar si cambia — nada más en la app conoce ese nombre (la consulta `IMPORTED_PRODUCTS` lo pide como
variables de GraphQL, y el escritor lo lee de la misma configuración). Cambiarlo exige **reiniciar**
la app: se lee al arrancar.

### Por qué NO es el metafield de la app (`app--400283500545`)

El primer nombre que se dio fue `app--400283500545.discount_tiers`, el metafield propio de la app de
descuentos. **Shopify rechaza que otra app escriba ahí** — `app--<id>` es un namespace reservado —
con este `userError` por variante (probado en vivo con CM373BS el 2026-09-03):

```
Access to this namespace and key on Metafields for this resource type is not allowed.
```

`DiscountSyncService` reconoce ese error y devuelve `NOT_WRITTEN` explicando la salida, en vez de
reventar el import con un muro de GraphQL. La solución fue crear un metafield **del comerciante**,
`trophy_discount.discount_tiers`, que la app del escaparate lee.

### Verificado en vivo (2026-09-03)

| Producto | Resultado |
|---|---|
| CM373BS (`p-8807-…-shot-glass`) | `PUBLISHED` · valor en **10 variantes** (todas las que tienen escalera; `CM373RV` no la tiene, y por eso nada en el producto) |
| GI307 (`p-8893-the-noir-glass-wave…`) | `PUBLISHED` · **productWide**: valor en el producto (`1→0, 3→14, 6→29`) |

Leído de vuelta desde Shopify, el valor de cada variante de CM373BS es exactamente el payload del
cliente:

```json
{"currencyCode":"USD","tiers":[{"minQuantity":25,"discountAmount":0},{"minQuantity":75,"discountAmount":1},{"minQuantity":150,"discountAmount":4}]}
```

Antes hubo que **importar** el producto migrado (`POST /api/sync/products/CM373BS`): pasó de una sola
variante legacy `PS10906 / Default Title` a **11 variantes** con `Color`+`Size`, SKU por parte, precio
19.99, stock del proveedor y `custom.promo_standard_id` en cada una — que es lo que empareja cada
escalera con su variante.

> Nota de método: al sondear Shopify, la búsqueda **debe ir literal en el documento**
> (`products(query: "tag:promostandards")`). Pasándola como variable GraphQL devolvió una página
> vacía en una tienda con cientos de productos etiquetados — un falso negativo que costó una
> conclusión equivocada. `check_discount_metafield.py` ya la inyecta literal.

## 3. Producto y variante

- La escalera se escribe **en cada variante cubierta**, emparejando por `custom.promo_standard_id`
  (el id de PaceSetter que representa esa variante).
- Se escribe **también en el producto** sólo cuando una única escalera cubre **todos** los ids que el
  producto representa. Ese valor de producto es el que lee el badge del catálogo (viaja en el índice
  cacheado `IMPORTED_PRODUCTS`, así que la insignia no cuesta ninguna petición extra).
- Un producto agrupado cuyas partes descuentan distinto (EP2 son −51 y EP2PK −106 a las 6 unidades,
  en el mismo producto de Shopify) obtiene **un valor por variante y ninguno en el producto**: un
  importe común sería $55 falso para una de las dos. `DiscountSyncService` agrupa los ids **por el JSON exacto**
  que producen, así que dos ids que valen lo mismo comparten valor y los demás no.
- Si sólo algunos de los ids del producto tienen escalera, tampoco se escribe en el producto: heredar
  la escalera de otro id sería un descuento inventado. Queda un `warning` en la respuesta.

## 4. La regla de cálculo (confirmada por el cliente, 2026-08-28) — sin cambios

Todo sale de `PricingPolicy.retailPrice`, el mismo método que pone el precio de la variante, así que
la escalera no puede descuadrarse del precio publicado.

```
base            = round99(list(0))     # GI307: 208.00 -> 207.99
precio(i)       = round99(list(i))     #   qty 3: 194.00 -> 193.99
discountAmount  = base − precio(i)     #   qty 3: 14      qty 6: 29
```

- Regla de redondeo: **el mayor x.99 que no supere el precio** cuando el precio lo dicta el proveedor
  (nunca publicamos por encima de lo que PaceSetter declara); hacia arriba cuando el precio lo calcula
  la app con markup (13.30 → 13.99, para no comerse el margen).
- Cada tramo se redondea **antes** de compararse con la base, de modo que todo lo que ve el comprador
  acaba en .99: CD1268 → 109.99 / 84.99 / 69.99 / 61.99 / 54.99.
- Caso a vigilar: con céntimos por encima de .50 el redondeo hacia abajo baja más de un céntimo
  (84.75 → 83.99). Si algún día resulta demasiado agresivo en artículos baratos, la variante es "el
  x.99 más cercano" y es una comparación en `PricingPolicy.charmDown`.

## 5. Código

| Pieza | Qué hace |
|---|---|
| `discount/QuantityLadder` | tabla del proveedor → base + descuento por unidad y cantidad (todo vía `PricingPolicy`) |
| `discount/QuantityDiscountJson` | la escalera → el JSON exacto del metafield |
| `discount/DiscountProperties` | `discounts.*`: on/off y namespace/key/type |
| `discount/DiscountSyncService` | agrupa ids por escalera, escribe variantes + producto, avisa |
| `discount/web/DiscountController` | `GET /status`, `GET /preview/{id}`, `POST /products/{id}`, `POST /products` |
| `sync/ShopifySyncService` | `setVariantMetafield` / `setProductMetafield` / `hasDiscounts` (badge) |

`POST /api/sync/products/{id}` publica los descuentos **después de importar** (en `SyncController`):
un producto cuya escalera no está publicada tiene mal precio en cuanto se pasa del primer tramo. Un
fallo ahí se reporta en `discountError` y nunca hace fallar la importación.

Tests: `QuantityLadderTest` (tablas reales de PaceSetter), `DiscountSyncServiceTest` (el JSON exacto,
el reparto EP2/EP2PK, la guarda del valor de producto), `DiscountApiTest` (configuración + preview
contra el proveedor stub).

## 6. Pendiente

1. **Confirmar en la app del escaparate** que lee `trophy_discount.discount_tiers` en la **variante**
   (no sólo en el producto): los agrupados con partes de precio distinto sólo pueden llevarlo por
   variante.
2. **Borrado**: un producto que deja de tener escalera conserva el valor antiguo; hoy nada borra
   metafields. Hace falta `metafieldsDelete` (o escribir `tiers: []`, si la app del escaparate lo
   interpreta como "sin descuentos" — hay que preguntarlo).
3. **Cantidad mínima de pedido** (CM297 empieza en 4): se publica como primer tramo y se avisa en la
   consola, pero no se fuerza en el carrito.
4. **Sincronización masiva/automática**: existe `POST /api/discounts/products` (lote) pero no hay un
   job programado. Ya no hay impedimento técnico (no hay token que caduque), es sólo decidir cadencia.
5. **Filas de inventario basura** que meten variantes sin precio: pendiente desde el 2026-09-02, no es
   propio de los descuentos.
