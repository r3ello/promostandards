# Simulación de sync — tienda vs PaceSetter (2026-10-13 18:21)

Simulación de solo lectura de `POST /api/sync/products/{id}` sobre todos los productos PromoStandards de `trophy-partner`. No se escribió nada. Generado por `tools/simulate_store_sync.py`; el detalle fila a fila está en los CSV junto a este fichero. La app **no crea productos** (`sync.create-products.enabled=false`): sólo actualiza los que ya están en Shopify.

## 0. Productos sanos — se pueden migrar ya, sólo a nivel de variantes

Sano = todo a la vez: está en Shopify (no se crea nada); todos sus ids son productos vivos de PaceSetter; todos son de **una sola familia** de PaceSetter y ningún otro producto de la tienda tiene ids de esa familia; y ninguna variante que traería la sync está en la lista de otro producto ni acabaría en otro.

**409 productos sanos**, que pasarían de 853 variantes a ≈853. 0 de ellos completarían su familia con ids que su lista no tenía (variantes reales de esa misma familia que no reclama ningún otro producto).

Lista: `healthy_products.txt`, un id por línea, para `python tools/sync_store_catalog.py --only @<ruta>/healthy_products.txt --report <run>.jsonl`.

| por qué no es sano (un producto puede tener varios motivos) | productos | sólo por este motivo |
|---|---|---|
| ids que no son un producto vivo | 442 | 218 |
| varias familias | 228 | 93 |
| sin tag o sin ids | 208 | 208 |
| familia compartida con otro producto | 109 | 1 |
| variantes que lista otro producto | 3 | 0 |

| producto sano | ids | familia PaceSetter | variantes hoy → tras la sync |
|---|---|---|---|
| [14 oz. Pilsner Tumbler](https://admin.shopify.com/store/trophy-partner/products/15512178589807) | CM717* | CM717* | 16 → 16 |
| [15 oz. Stemless Wine Glass](https://admin.shopify.com/store/trophy-partner/products/15512181604463) | G0991 | G0991 | 1 → 1 |
| [16 oz. Pint Mixing Glass](https://admin.shopify.com/store/trophy-partner/products/15512181440623) | G0992 | G0992 | 1 → 1 |
| [16 oz. Willi Becher Beer Glass](https://admin.shopify.com/store/trophy-partner/products/15512181375087) | G0994 | G0994 | 1 → 1 |
| [18 Wheeler Truck - Med](https://admin.shopify.com/store/trophy-partner/products/15530953834607) | GNS179B | GNS179B | 1 → 1 |
| [18 Wheeler Truck - Small](https://admin.shopify.com/store/trophy-partner/products/15530953605231) | GNS179A | GNS179A | 1 → 1 |
| [18 Wheeler Truck, Large](https://admin.shopify.com/store/trophy-partner/products/15530953998447) | GNS179C | GNS179C | 1 → 1 |
| [19 oz. Wine Glass](https://admin.shopify.com/store/trophy-partner/products/15512181276783) | G0996 | G0996 | 1 → 1 |
| [20 oz Polar Camel Travel Mug, Blue](https://admin.shopify.com/store/trophy-partner/products/15530937974895) | CM815* | CM815* | 17 → 17 |
| [20 oz. Pilsner Tumbler](https://admin.shopify.com/store/trophy-partner/products/15512178524271) | CM716* | CM716* | 16 → 16 |
| [3 Tier Glass Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512270864495) | GI603 | GI603 | 2 → 2 |
| [3/8" Thick Lucite Wall Plaque with Custom Digi-Color](https://admin.shopify.com/store/trophy-partner/products/15530926801007) | CD267A | CD267A | 1 → 1 |
| [3/8" Thick Lucite Wall Plaque with Custom Digi-Color](https://admin.shopify.com/store/trophy-partner/products/15530927325295) | CD267B | CD267B | 1 → 1 |
| [3/8” Thick Custom Digi Small](https://admin.shopify.com/store/trophy-partner/products/15512212144239) | CD267C | CD267C | 1 → 1 |
| [6 oz. Stainless Steel Lasered Flask](https://admin.shopify.com/store/trophy-partner/products/15512178425967) | CM254 | CM254 | 2 → 2 |
| [6-1/2 x 6 x 2-3/4 Red Art Glass Heart on Black Gla](https://admin.shopify.com/store/trophy-partner/products/15530948657263) | GM761 | GM761 | 1 → 1 |
| [7-Piece Leatherette Manicure Gift Set](https://admin.shopify.com/store/trophy-partner/products/15512178131055) | CM293 | CM293 | 6 → 6 |
| [8 oz. Champagne Flute](https://admin.shopify.com/store/trophy-partner/products/15512181211247) | G0995 | G0995 | 1 → 1 |
| [9 oz. Stemless Wine Glass](https://admin.shopify.com/store/trophy-partner/products/15512181080175) | G0990 | G0990 | 1 → 1 |
| [Acrylic Awareness Ribbon Awards](https://admin.shopify.com/store/trophy-partner/products/15512160960623) | CD1268 | CD1268 | 1 → 1 |
| [Alder Wood Oval Name Bar](https://admin.shopify.com/store/trophy-partner/products/15512269815919) | CM464 | CM464 | 1 → 1 |
| [Alder Wood Rectangle Name Bar](https://admin.shopify.com/store/trophy-partner/products/15512269619311) | CM465 | CM465 | 1 → 1 |
| [Angular Clear Glass Award](https://admin.shopify.com/store/trophy-partner/products/15512210407535) | GI672 | GI672 | 2 → 2 |
| [Angular Perpetual Award](https://admin.shopify.com/store/trophy-partner/products/15512210112623) | GI668 | GI668 | 1 → 1 |
| [Anniversary Achievement Award, 1 Year](https://admin.shopify.com/store/trophy-partner/products/15530927915119) | CD901Y* | CD901Y* | 14 → 14 |
| [Anniversary Achievement Years of Service Awards](https://admin.shopify.com/store/trophy-partner/products/15512269488239) | CD903Y_ | CD903Y_ | 17 → 17 |
| [Anniversary Freestanding Years of Service Award](https://admin.shopify.com/store/trophy-partner/products/15512269095023) | CD904AY* | CD904AY* | 5 → 5 |
| [Anniversary Service Recognition Awards](https://admin.shopify.com/store/trophy-partner/products/15512210014319) | CD981Y* | CD981Y* | 12 → 12 |
| [Anniversary Years of Service Achievement Plaque](https://admin.shopify.com/store/trophy-partner/products/15512269357167) | CD902Y* | CD902Y* | 14 → 14 |
| [Anniversary Years of Service Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512269193327) | CD904BY* | CD904BY* | 9 → 9 |
| [Aquus Series Lucite Peak Award](https://admin.shopify.com/store/trophy-partner/products/15512268865647) | CDUS02C | CDUS02C | 1 → 1 |
| [Arched Brilliance Lucite Award on Clear Base](https://admin.shopify.com/store/trophy-partner/products/15512265326703) | CD636 | CD636 | 3 → 3 |
| [Art Glass Award on Glass Base with Plate](https://admin.shopify.com/store/trophy-partner/products/15512268734575) | GM577 | GM577 | 1 → 1 |
| [Art Glass Black & Gold Coral Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512268472431) | GM639 | GM639 | 1 → 1 |
| [Art Glass Black & Gold Curve on Base Award](https://admin.shopify.com/store/trophy-partner/products/15512268046447) | GM640 | GM640 | 1 → 1 |
| [Art Glass Blue & Gold Drop On Base](https://admin.shopify.com/store/trophy-partner/products/15512268013679) | GM637 | GM637 | 1 → 1 |
| [Art Glass Green And Gold Drop On Base](https://admin.shopify.com/store/trophy-partner/products/15512267849839) | GM638 | GM638 | 1 → 1 |
| [Art Glass Green Spiral on Black Glass Base Award](https://admin.shopify.com/store/trophy-partner/products/15512267751535) | GI206 | GI206 | 1 → 1 |
| [Ball Alder Ornament](https://admin.shopify.com/store/trophy-partner/products/15512209850479) | CM757A | CM757A | 2 → 2 |
| [Bamboo BBQ Set](https://admin.shopify.com/store/trophy-partner/products/15512267325551) | CM416 | CM416 | 1 → 1 |

… 369 más en el CSV.

## Respuestas directas

### 1. ¿Cuántos productos de Shopify están disponibles en PaceSetter? (1154 productos)

| situación de sus ids | productos | qué hace la sync |
|---|---|---|
| todos existen como producto en PaceSetter | 504 | se sincroniza completo |
| algunos sí, otros no | 23 | se sincroniza con los que existen; los demás se ignoran |
| ninguno es producto, pero son **variantes** de un producto PaceSetter | 167 | no se sincroniza hasta que su lista apunte al producto (ver 3.2) |
| ninguno existe en PaceSetter | 460 | no se sincroniza |

**Disponibles: 527 de 1154.** Otros 167 lo serían si se corrige su lista de ids.

### 2. ¿Dos o más productos de Shopify reclaman lo mismo?

| nivel | casos | productos de Shopify implicados |
|---|---|---|
| el **mismo id** en la lista de dos productos | 0 | 0 |
| el **mismo producto PaceSetter** (por su id o por sus variantes) | 43 | 106 |
| la **misma variante PaceSetter** en dos productos **después** de sincronizar | 0 | 0 |

- **Mismo id: 0.** Ningún id aparece en dos productos, así que el índice de la app siempre resuelve a un único producto.
- **Mismo producto PaceSetter: 43.** La tienda reparte un producto PaceSetter entre varios productos de Shopify, casi siempre uno por color o por medida, cada uno con el id de su variante (p. ej. las Halley Plaque Small/Medium/Large × Red/Gold/Green son todas variantes de `C750`). Hoy no chocan porque cada uno lista un id distinto, pero ninguno se sincroniza así (ver 3.2).
- **Misma variante tras la sync: 0 variantes en 0 productos.** Aquí sí habría duplicados: al sincronizar, la app añade las variantes que el Inventory de PaceSetter devuelve para toda la familia, y donde la tienda vende cada color o estado como producto propio, cada uno acabaría con las variantes de todos los demás (ver 3.6). Hay además 13 ids de variante que PaceSetter declara en varios productos a la vez; no se atribuyen a ninguno.

| producto PaceSetter | nombre | productos de Shopify que lo reclaman |
|---|---|---|
| `C750` |  | 10: Blue Halley Collection Plaque; Large Gold Halley Plaque; Large Green Halley Plaque; Large Red Halley Plaque (+6) |
| `C701BL` | Chisel Carve Tower on Base | 7: Black Chisel Carve Tower Award on Base; Blue Chisel Carved Tower Award On Base; Gold Chisel Carved Tower on Base Award; Green Chisel Carve Tower Award on Base (+3) |
| `C701L` | Chisel Carve Tower | 7: Black Chisel Carve Tower Award on Base; Blue Chisel Carve Tower Award; Gold Chisel Carve Tower Award; Green Chisel Carve Tower Award (+3) |
| `C023` |  | 6: Blue Plaque w/ Marble Mist Small; Blue Plaque w/ Marble Mist Large; Red Plaque w/ Marble Mist Small; Red Plaque with Marble Mist Large (+2) |
| `CD414*` | Blue Marble Florentine Plate on Ebony Board | 6: Blue Marble Florentine Plate on Ebony Finish Board; Red Marble Florentine Plate on Ebony Board; Blue Marble Florentine Plate on Ebony Board; Green Marble Florentine Plate on Ebony Board (+2) |
| `C612LB` |  | 4: Blue Chisel Carved Tower Award On Base; Gold Chisel Carved Tower on Base Award; Gold Spectrum Chisel Carved Tower Award; Blue Spectrum Chisel Carved Tower Small |
| `DCCD520` |  | 4: Ebony Finish Plaque with choice of plate and board; Ebony Piano Finish Plaque; Choice of Digi Small; Choice of Digi Large |
| `C072` |  | 3: Gold Plaque w/ Black with Gold Florentine Plate; Genuine Walnut Elliptical Plaque with Black/Silver Florentine Small; Genuine Walnut Elliptical Plaque with Black/Silver Florentine Large |
| `CD547A` |  | 3: Digi-color Mahogany Plaque; Medium Digi; Walnut Finish Digi |
| `CD547B` | Large Digi-Color Direct Plaque | 3: Digi-color Mahogany Plaque; Walnut Finish Digi; Large Digi Large |
| `CD547C` | Extra-Large Digi-Color Direct Plaque | 3: Digi-color Mahogany Plaque; Walnut Finish  Digi; Extra-Large Digi |
| `CM712` | Polar Camel 12 oz. Stemless Tumbler | 3: Polar Camel Stemless Wine Tumblers Personalized; Polar Camel Small; Polar Camel Medium |
| `GM453` |  | 3: Optic Clear Crystal Cube XX Large Award; Optic Clear Crystal Cube X Large Award Trophy; Optic Clear Crystal Cube Award Trophy |
| `C075` |  | 2: Simplicity Plate on Walnut Finish Board Plaque; Simplicity Plate on Walnut Finish Board |
| `C076` |  | 2: Simplicity Plate on Ebony Board Plaque; Simplicity Plate on Ebony Board |
| `C609LB` | Small Chisel Carve Tower on Base | 2: Blue Chisel Carved Tower Award On Base; Gold Chisel Carved Tower On Base Large |
| `C609LGO` | Small Chisel Carve Tower | 2: Gold Spectrum Chisel Carved Tower Trophy Award; Blue Spectrum Chisel Carved Tower Large |
| `C610LB` | Medium Chisel Carve Tower on Base | 2: Blue Chisel Carved Tower Award On Base; Gold Chisel Carved Tower on Base Award |
| `CD1053` |  | 2: Golf Course Silhouette Award; Golf Course Silhouette Award Trophy |
| `CD1217` | 7½" x 11 7/8" x 3" Blue Cascade Curved Acrylic Award | 2: Red Cascade Carved Acrylic Award; Blue Cascade Carved Acrylic Award |
| `CD1222` | 9" x 12" Black and Clear Acrylic Beveled Perpetual Plaque with Standoffs; Free Personals; Laser engraved or Digi-color; Digi-Color orders less than 6 pieces incur a less than minimum charge, $60.00 (V); | 2: Black and Clear Acrylic Beveled Perpetual Plaque; Clear Acrylic Perpetual Plate |
| `CD549` |  | 2: Freestanding Lucite Block; Lucite Square & Rectangular Block Awards |
| `CD603` |  | 2: Blue Gemstone Brilliance Lucite Peak Award; Green Gemstone Brilliance Lucite Peak Award |
| `CD852` |  | 2: Blue Designs Deco Lucite Tablet; Red Designs Deco Lucite Tablet Award Trophy |
| `CD854` |  | 2: Blue Deco Lucite Peak on Marble Base Award; Red Deco Lucite Peak On Marble Base Award Trophy |

… 18 más en el CSV.

| familia PaceSetter | productos que compartirían variantes tras la sync |
|---|---|

### 3. ¿De qué está hecha la lista de ids de cada producto?

Un id puede ser un **producto** de PaceSetter (tiene su propia ficha y precio) o sólo una **variante** (una parte que PaceSetter vende dentro de otro producto).

| composición de `ps_product_ids` | productos | ejemplo |
|---|---|---|
| 1 id, que es un producto PaceSetter | 424 | Acrylic Awareness Ribbon Awards; The Noir Glass Wave On Black Glass Base (+422) |
| 1 id, que es una variante de otro producto PaceSetter | 57 | Teal Chisel Carve Tower Award; Teal Chisel Carve Tower Award on Base (+55) |
| 1 id, que no existe | 123 | Water Bottle Carabiner Small; Turquoise and Stone Lucite 13 Plate Photo Plaque (+121) |
| **varios productos PaceSetter** de la misma familia (colores que PaceSetter vende por separado) | 27 | Zipper Leatherette Portfolio; Leatherette Wine Bag (+25) |
| **varios productos PaceSetter** de familias distintas (medidas, modelos) | 104 | Interlocking Perpetual Award Bars; Scroll Border Photo Plaque (+102) |
| un producto PaceSetter + variantes | 3 | Gold Finish Plastic Cup on Marble Base; Slide-in Certificate Plaque - Ebony Finish (+1) |
| un producto PaceSetter + ids que no existen | 8 | Crimson Border Beveled Lucite; Blue Steel Contoured Lucite (+6) |
| sólo variantes | 110 | Taper Edge Award; Tahoe Drinkware (+108) |
| varios ids, ninguno existe | 298 | Water Drop Award; Water Bottle Carabiner Large (+296) |

**Productos cuya lista reúne varios productos PaceSetter: 131.** Es la agrupación N:1 de la migración: la sync los convierte en variantes de un único producto de Shopify. Los de familias distintas son los que conviene revisar a mano (la tabla 3.3 muestra los nombres).

## Detalle

### Estado de partida

|  | cantidad |
|---|---|
| Productos en la tienda | 5062 |
| … PromoStandards (tag o ids) | 1154 |
| … ya sincronizados por la app (`trophy_sync.last_sync_at`) | 547 |
| … con 1 / 2 / 3+ variantes hoy | 944 / 78 / 132 |
| Handles creados por la app (`ps-…`) | 0 |
| Ids de PaceSetter distintos que reclama la tienda | 1929 |
| Ids vendibles de PaceSetter (`getProductSellable`) | 1278 |
| … de ellos, familias con comodín (`CM717*`) | 55 |

### Qué son los ids de la tienda en PaceSetter

| tipo | ids | significado |
|---|---|---|
| PRODUCT | 922 | vendible, con ficha y tabla de precios — puede arrancar una sync |
| PRODUCT_UNLISTED | 0 | no está en la lista de vendibles pero responde ficha y precio |
| NO_PRICE | 8 | tiene ficha pero no precio — la sync lo rechaza (saldría a 0) |
| NO_PRODUCT_DATA | 42 | vendible, pero Product Data no tiene partes |
| PART | 507 | **no es un producto: es una variante de otro producto PaceSetter** |
| FAMILY_PART | 13 | cae bajo un código de familia con comodín, pero ninguna respuesta lo lista |
| ORPHAN | 437 | sin rastro — descatalogado o recodificado |

### Veredicto por producto

| veredicto | productos | qué pasa |
|---|---|---|
| OK | 429 | se sincroniza; todos sus ids vivos son una misma línea de PaceSetter |
| MULTI_PRODUCT | 98 | se sincroniza uniendo **varias líneas de PaceSetter** en un producto |
| COLLISION | 0 | un id está también en otro producto — **bloqueante** |
| UNSYNCABLE_PARTS | 167 | ningún id arranca, pero son variantes de un producto vivo |
| UNSYNCABLE | 252 | ningún id que PaceSetter sirva |
| NO_IDS | 208 | con tag pero sin `ps_product_id(s)` |

### 3.2 Ids que son variantes de otro producto PaceSetter

De los 167 productos que sólo tienen variantes:

- **68 simples** — ningún otro producto de la tienda tiene variantes de ese producto PaceSetter: basta con poner el id del producto en su lista.
- **99 repartidos** — la tienda dividió un producto PaceSetter en varios de Shopify (por color, medida o estado). Apuntarlos todos al mismo id los haría colisionar, y sincronizar cualquiera se llevaría **todas** sus variantes. Hay que decidir: fusionarlos en un producto de Shopify, o que la app admita que un producto cubra sólo *algunas* variantes de un producto PaceSetter.

| producto | id de variante → producto PaceSetter | estado |
|---|---|---|
| [Teal Chisel Carve Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512162173039) | `C701LTE` → C701L | UNSYNCABLE_PARTS · repartido |
| [Teal Chisel Carve Tower Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512162205807) | `C701BLTE` → C701BL | UNSYNCABLE_PARTS · repartido |
| [Taper Edge Award](https://admin.shopify.com/store/trophy-partner/products/15512162238575) | `GM750ABK` → GM750; `GM750ABL` → GM750; `GM750BBK` → GM750; `GM750BBL` → GM750; `GM751ABK` → GM751 | UNSYNCABLE_PARTS · simple |
| [Tahoe Drinkware](https://admin.shopify.com/store/trophy-partner/products/15512163352687) | `CM814BK` → CM814; `CM814BL` → CM814; `CM814CR` → CM814; `CM814DB` → CM814; `CM814GR` → CM814; `CM814GY` → CM814; `CM814LB` → CM814; `CM814LP` → CM814; `CM814MR` → CM814; `CM814OR` → CM814; `CM814PK` → CM814; `CM814PU` → CM814; `CM814RD` → CM814; `CM814TL` → CM814; `CM814WH` → CM814; `CM814YW` → CM814 | UNSYNCABLE_PARTS · simple |
| [Small Red Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512163975279) | `C750ALR` → C750 | UNSYNCABLE_PARTS · repartido |
| [Small Green Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512164106351) | `C750ALGN` → C750 | UNSYNCABLE_PARTS · repartido |
| [Small Gold Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512164204655) | `C750ALGO` → C750 | UNSYNCABLE_PARTS · repartido |
| [Simplicity Plate on Walnut Finish Board](https://admin.shopify.com/store/trophy-partner/products/15512164499567) | `C075BB` → C075; `C075BGO` → C075; `C075BR` → C075 | UNSYNCABLE_PARTS · repartido |
| [Simplicity Plate on Ebony Board](https://admin.shopify.com/store/trophy-partner/products/15512164630639) | `C076BB` → C076; `C076BGO` → C076 | UNSYNCABLE_PARTS · repartido |
| [Red Marble Florentine Plate on Walnut Finish Board](https://admin.shopify.com/store/trophy-partner/products/15512165679215) | `CD414ARWF` → CD414*; `CD414BRWF` → CD414* | UNSYNCABLE_PARTS · repartido |
| [Polar Camel Medium](https://admin.shopify.com/store/trophy-partner/products/15512166629487) | `CM712BL` → CM712; `CM712DB` → CM712; `CM712GR` → CM712; `CM712GY` → CM712; `CM712LB` → CM712; `CM712LP` → CM712; `CM712MR` → CM712; `CM712OR` → CM712; `CM712PK` → CM712; `CM712PU` → CM712; `CM712RD` → CM712; `CM712TL` → CM712; `CM712WH` → CM712; `CM713BK` → CM713*; `CM731BK` → CM731 | UNSYNCABLE_PARTS · repartido |
| [Polar Camel Small](https://admin.shopify.com/store/trophy-partner/products/15512166695023) | `CM713BL` → CM713*; `CM713DB` → CM713*; `CM713GR` → CM713*; `CM713GY` → CM713*; `CM713LB` → CM713*; `CM713LP` → CM713*; `CM713MR` → CM713*; `CM713OR` → CM713*; `CM713PK` → CM713*; `CM713PU` → CM713*; `CM713RD` → CM713*; `CM713TL` → CM713*; `CM713WH` → CM713*; `CM711BL` → CM711; `CM711CR` → CM711; `CM711DB` → CM711; `CM711GR` → CM711; `CM711GY` → CM711; `CM711LB` → CM711; `CM711LP` → CM711; `CM711MR` → CM711; `CM711OG` → CM711; `CM711OR` → CM711; `CM711PK` → CM711; `CM711PU` → CM711; `CM711RD` → CM711; `CM711TL` → CM711; `CM711WH` → CM711; `CM711YW` → CM711; `CM712BK` → CM712; `CM726BL` → CM726*; `CM726CR` → CM726*; `CM726DB` → CM726*; `CM726GR` → CM726*; `CM726GY` → CM726*; `CM726LB` → CM726*; `CM726LP` → CM726*; `CM726MR` → CM726*; `CM726OR` → CM726*; `CM726PK` → CM726*; `CM726PU` → CM726*; `CM726RD` → CM726*; `CM726TL` → CM726*; `CM726WH` → CM726*; `CM726YW` → CM726*; `CM731BL` → CM731; `CM731CR` → CM731; `CM731DB` → CM731; `CM731GR` → CM731; `CM731GY` → CM731 | UNSYNCABLE_PARTS · repartido |
| [Polar Camel stainless steel beverage holder. Large](https://admin.shopify.com/store/trophy-partner/products/15512166826095) | `CM727BL` → CM727*; `CM727CR` → CM727*; `CM727DB` → CM727*; `CM727GR` → CM727*; `CM727GY` → CM727*; `CM727LB` → CM727*; `CM727LP` → CM727*; `CM727MR` → CM727*; `CM727OR` → CM727*; `CM727PK` → CM727*; `CM727PU` → CM727*; `CM727RD` → CM727*; `CM727TL` → CM727*; `CM727WH` → CM727*; `CM727YW` → CM727*; `CM737BL` → CM737*; `CM737CR` → CM737*; `CM737DB` → CM737*; `CM737GR` → CM737*; `CM737GY` → CM737*; `CM737LB` → CM737*; `CM737LP` → CM737*; `CM737MR` → CM737*; `CM737OR` → CM737*; `CM737PK` → CM737*; `CM737PU` → CM737*; `CM737RD` → CM737*; `CM737TL` → CM737*; `CM737WH` → CM737*; `CM737YW` → CM737* | UNSYNCABLE_PARTS · repartido |
| [Polar Camel stainless steel beverage holder. Small](https://admin.shopify.com/store/trophy-partner/products/15512166957167) | `CM727BK` → CM727*; `CM737BK` → CM737* | UNSYNCABLE_PARTS · repartido |
| [Plaque w/ Green Marble Mist](https://admin.shopify.com/store/trophy-partner/products/15512167088239) | `C023AGR` → C023 | UNSYNCABLE_PARTS · repartido |
| [Medium Red Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512168169583) | `C750BLR` → C750 | UNSYNCABLE_PARTS · repartido |
| [Medium Green Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512168202351) | `C750BLGN` → C750 | UNSYNCABLE_PARTS · repartido |
| [Medium Gold Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512168267887) | `C750BLGO` → C750 | UNSYNCABLE_PARTS · repartido |
| [Leatherette Pen](https://admin.shopify.com/store/trophy-partner/products/15512169775215) | `CM356BB` → CM356BL, CM356BS, CM356DB, CM356GR, CM356LB, CM356PK, CM356RS, CM356RW, CM356TL; `CM356BV` → CM356BL, CM356BS, CM356DB, CM356GR, CM356LB, CM356PK, CM356RS, CM356RW, CM356TL | OK · simple |
| [Leatherette on Steel Shot Glass](https://admin.shopify.com/store/trophy-partner/products/15512169807983) | `CM373RV` → CM373BB, CM373BS, CM373BV, CM373DB, CM373GR, CM373LB, CM373PK, CM373RW, CM373TL, CM373WM | OK · simple |
| [Large Red Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512171610223) | `C750CLR` → C750 | UNSYNCABLE_PARTS · repartido |
| [Large Green Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512171642991) | `C750CLGN` → C750 | UNSYNCABLE_PARTS · repartido |
| [Large Gold Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512171708527) | `C750CLGO` → C750 | UNSYNCABLE_PARTS · repartido |
| [Large Digi Large](https://admin.shopify.com/store/trophy-partner/products/15512171806831) | `CD547BEB` → CD547B | UNSYNCABLE_PARTS · repartido |
| [Green Vibrant Gemstone Award](https://admin.shopify.com/store/trophy-partner/products/15512172134511) | `GI512AGR` → GI512; `GI512BGR` → GI512 | UNSYNCABLE_PARTS · repartido |
| [Green Marble Florentine Plate on Walnut Finish Board](https://admin.shopify.com/store/trophy-partner/products/15512172462191) | `CD414AGRWF` → CD414*; `CD414BGRWF` → CD414* | UNSYNCABLE_PARTS · repartido |
| [Green Marble Florentine Plate on Ebony Board](https://admin.shopify.com/store/trophy-partner/products/15512172560495) | `CD414AGREF` → CD414*; `CD414BGREF` → CD414* | UNSYNCABLE_PARTS · repartido |
| [Gold Chisel Carved Tower On Base Large](https://admin.shopify.com/store/trophy-partner/products/15512172986479) | `C609LBGO` → C609LB | UNSYNCABLE_PARTS · repartido |
| [Genuine Walnut Elliptical Plaque with Marble Mist](https://admin.shopify.com/store/trophy-partner/products/15512173117551) | `C023BGR` → C023 | UNSYNCABLE_PARTS · repartido |
| [Genuine Walnut Elliptical Plaque with Black/Silver Florentine Large](https://admin.shopify.com/store/trophy-partner/products/15512173183087) | `C072AS` → C072 | UNSYNCABLE_PARTS · repartido |
| [Genuine Walnut Elliptical Plaque with Black/Silver Florentine Small](https://admin.shopify.com/store/trophy-partner/products/15512173314159) | `C072BS` → C072 | UNSYNCABLE_PARTS · repartido |
| [Florentia Acrylic Plaque](https://admin.shopify.com/store/trophy-partner/products/15512173641839) | `CD973ABL` → CD973; `CD973ARD` → CD973; `CD973BBL` → CD973; `CD973BRD` → CD973 | UNSYNCABLE_PARTS · simple |
| [Choice of Digi Large](https://admin.shopify.com/store/trophy-partner/products/15512175706223) | `DCC3001BWN` → DCC3001BEB; `DCCD520DGA` → DCCD520; `DCCD520DRW` → DCCD520; `DCCD520DWN` → DCCD520 | UNSYNCABLE_PARTS · repartido |
| [Choice of Digi Small](https://admin.shopify.com/store/trophy-partner/products/15512175738991) | `DCCD520EGA` → DCCD520; `DCCD520ERW` → DCCD520; `DCCD520EWN` → DCCD520 | UNSYNCABLE_PARTS · repartido |
| [Cascade Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512176066671) | `CD1024BL` → CD1024; `CD1024CL` → CD1024 | UNSYNCABLE_PARTS · simple |
| [Blue Spectrum Chisel Carved Tower Large](https://admin.shopify.com/store/trophy-partner/products/15512176459887) | `C609LBL` → C609LGO | UNSYNCABLE_PARTS · simple |
| [Blue Spectrum Chisel Carved Tower Small](https://admin.shopify.com/store/trophy-partner/products/15512176525423) | `C612LBL` → C612LB | UNSYNCABLE_PARTS · repartido |
| [Blue Marble Florentine Plate on Ebony Board](https://admin.shopify.com/store/trophy-partner/products/15512176623727) | `CD414ABEF` → CD414* | UNSYNCABLE_PARTS · repartido |
| [Blue Halley Collection Plaque](https://admin.shopify.com/store/trophy-partner/products/15512176918639) | `C750ALB` → C750; `C750BLB` → C750; `C750CLB` → C750 | UNSYNCABLE_PARTS · repartido |
| [Blue Cascade Carved Acrylic Award](https://admin.shopify.com/store/trophy-partner/products/15512177279087) | `CD1217BL` → CD1217 | UNSYNCABLE_PARTS · repartido |
| [Sweeping Success Acrylic Award](https://admin.shopify.com/store/trophy-partner/products/15512178950255) | `CD1249ABL` → CD1249; `CD1249AGR` → CD1249; `CD1249ARD` → CD1249; `CD1249BBL` → CD1249; `CD1249BGR` → CD1249; `CD1249BRD` → CD1249; `CD1249CBL` → CD1249; `CD1249CGR` → CD1249; `CD1249CRD` → CD1249 | UNSYNCABLE_PARTS · simple |
| [Rounded Acrylic Highlighter Award](https://admin.shopify.com/store/trophy-partner/products/15512179245167) | `CD1252ABG` → CD1252; `CD1252AOR` → CD1252; `CD1252APB` → CD1252; `CD1252BBG` → CD1252; `CD1252BOR` → CD1252; `CD1252BPB` → CD1252; `CD1252CBG` → CD1252; `CD1252COR` → CD1252; `CD1252CPB` → CD1252 | UNSYNCABLE_PARTS · simple |
| [Optic Crystal Wedge with Colorful Wave Design](https://admin.shopify.com/store/trophy-partner/products/15512179310703) | `GI843ABL` → GI843; `GI843AGR` → GI843; `GI843ARD` → GI843; `GI843BBL` → GI843; `GI843BGR` → GI843; `GI843BRD` → GI843; `GI843CBL` → GI843; `GI843CGR` → GI843; `GI843CRD` → GI843; `GI843DBL` → GI843; `GI843DGR` → GI843; `GI843DRD` → GI843 | UNSYNCABLE_PARTS · simple |
| [Optic Crystal Triad Tower](https://admin.shopify.com/store/trophy-partner/products/15512179376239) | `GI838A` → GI838; `GI838B` → GI838 | UNSYNCABLE_PARTS · simple |
| [Optic Crystal Tower with Wood Block Base](https://admin.shopify.com/store/trophy-partner/products/15512179507311) | `GM845A` → GM845; `GM845B` → GM845 | UNSYNCABLE_PARTS · simple |
| [Optic Crystal Rectangle Award with Full Color](https://admin.shopify.com/store/trophy-partner/products/15512179605615) | `GI840AFL` → GI840; `GI840ALR` → GI840; `GI840ASP` → GI840; `GI840BFL` → GI840; `GI840BLR` → GI840; `GI840BSP` → GI840; `GI840CFL` → GI840; `GI840CLR` → GI840; `GI840CSP` → GI840 | UNSYNCABLE_PARTS · simple |
| [Optic Crystal Diamond Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512179671151) | `GI839A` → GI839; `GI839B` → GI839; `GI839C` → GI839 | UNSYNCABLE_PARTS · simple |
| [Optic Crystal Block with Stock Color Background](https://admin.shopify.com/store/trophy-partner/products/15512179900527) | `GI842ABL` → GI842; `GI842BBL` → GI842; `GI842CBL` → GI842 | UNSYNCABLE_PARTS · simple |
| [Nouveau Acrylic Award with Mirror Accent](https://admin.shopify.com/store/trophy-partner/products/15512180097135) | `CD1256ABL` → CD1256; `CD1256AGR` → CD1256; `CD1256ARD` → CD1256; `CD1256CBL` → CD1256; `CD1256CGR` → CD1256; `CD1256CRD` → CD1256 | UNSYNCABLE_PARTS · simple |
| [Faceted Optic Crystal Tower with Slanted Wood Block Base](https://admin.shopify.com/store/trophy-partner/products/15512180326511) | `GM846A` → GM846; `GM846B` → GM846 | UNSYNCABLE_PARTS · simple |
| [Acrylic Trinity Tower](https://admin.shopify.com/store/trophy-partner/products/15512180686959) | `CD1248ABL` → CD1248; `CD1248AGR` → CD1248; `CD1248ARD` → CD1248; `CD1248BBL` → CD1248; `CD1248BGR` → CD1248; `CD1248BRD` → CD1248; `CD1248CBL` → CD1248; `CD1248CGR` → CD1248; `CD1248CRD` → CD1248 | UNSYNCABLE_PARTS · simple |
| [Acrylic Chevron Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512180752495) | `CD1264ABL` → CD1264; `CD1264AGR` → CD1264; `CD1264ARD` → CD1264; `CD1264BBL` → CD1264; `CD1264BRD` → CD1264; `CD1264CBL` → CD1264; `CD1264CRD` → CD1264 | UNSYNCABLE_PARTS · simple |
| [Two-Tone Wood Alder Plaque](https://admin.shopify.com/store/trophy-partner/products/15512183013487) | `CD1037A` → CD1037; `CD1037B` → CD1037 | MULTI_PRODUCT · simple |
| [Slant Top Vase](https://admin.shopify.com/store/trophy-partner/products/15512191467631) | `G0500A` → G0500; `G0500B` → G0500; `G0500C` → G0500 | UNSYNCABLE_PARTS · simple |
| [Slant Top Crystal Cylinder Award](https://admin.shopify.com/store/trophy-partner/products/15512191533167) | `GM758A` → GM758; `GM758B` → GM758 | UNSYNCABLE_PARTS · simple |
| [Silver Star on Crystal Base with Silver Plate](https://admin.shopify.com/store/trophy-partner/products/15512191729775) | `GM625A` → GM625; `GM625B` → GM625 | UNSYNCABLE_PARTS · simple |
| [Rosewood Piano Finish Board with Raised Glass](https://admin.shopify.com/store/trophy-partner/products/15512192188527) | `G0986A` → G0986; `G0986B` → G0986 | UNSYNCABLE_PARTS · simple |
| [Rising Star Award](https://admin.shopify.com/store/trophy-partner/products/15512192352367) | `CD1218A` → CD1218; `CD1218B` → CD1218; `CD1218C` → CD1218 | UNSYNCABLE_PARTS · simple |
| [Red Marble Florentine Plate on Ebony Board](https://admin.shopify.com/store/trophy-partner/products/15512192712815) | `CD414AREF` → CD414*; `CD414BREF` → CD414* | UNSYNCABLE_PARTS · repartido |
| [Red Cascade Carved Acrylic Award](https://admin.shopify.com/store/trophy-partner/products/15512192843887) | `CD1217RD` → CD1217 | UNSYNCABLE_PARTS · repartido |

… 116 más en el CSV.

### 3.3 Un producto de Shopify, varias líneas de PaceSetter

No es un error por sí mismo — el catálogo antiguo agrupaba medidas y familias a propósito (p-8887). Pero donde los nombres de la derecha son artículos claramente distintos, deberían ser productos separados.

| producto | líneas | nombres en PaceSetter | ≈ variantes tras la sync |
|---|---|---|---|
| [2-Layer Multi-Dimensional Digi-Color Award](https://admin.shopify.com/store/trophy-partner/products/15512270962799) | DCC0501A DCC0501B | 2-Layer Multi-Dimensional Digi-Color Award | 2 |
| [2-Tone Bamboo Cutting Board](https://admin.shopify.com/store/trophy-partner/products/15512270930031) | CM419A CM419B | 2-Tone Bamboo Cutting Board - Large; 2-Tone Bamboo Cutting Board - Small | 2 |
| [Acrylic Color Drops Award](https://admin.shopify.com/store/trophy-partner/products/15512207327343) | CD1101A CD1101B CD1101C | Blue Acrylic Color Drops, Large; Blue Acrylic Color Drops, Med; Blue Acrylic Color Drops, Small | 9 |
| [Aquus Lucite Peak Award](https://admin.shopify.com/store/trophy-partner/products/15512268931183) | CDUS02A CDUS02B | Aquus Lucite Peak Award - Medium; Aquus Lucite Peak Award - Small | 3 |
| [Aquus Series Lucite Tablet Award](https://admin.shopify.com/store/trophy-partner/products/15512268800111) | CDUS01A CDUS01B | Aquus Lucite Tablet Award - Large; Aquus Lucite Tablet Award - Medium | 2 |
| [Ashbourne Series Diamond On Base](https://admin.shopify.com/store/trophy-partner/products/15512267522159) | GM410A GM410B GM410C | Diamond on Base - Large; Diamond on Base - Medium; Diamond on Base - Small | 3 |
| [Avignon Crescents Beveled Jade Glass Plaque Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512267391087) | GM456A GM456B | Beveled Jade Glass Crescent Plaque | 2 |
| [Beveled Crystal Circle Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512208932975) | GM757B GM757C | Beveled Crystal Circle, Lrg; Beveled Crystal Circle, Med | 2 |
| [Beveled Jade Glass Award Plaque](https://admin.shopify.com/store/trophy-partner/products/15512267423855) | GM456C GM456D | Beveled Jade Glass Crescent Plaque | 2 |
| [Beveled Jade Glass Crescent Plaque](https://admin.shopify.com/store/trophy-partner/products/15512266539119) | GM665A GM665B GM665C GM665D | Beveled Jade Glass Crescent Plaque | 4 |
| [Beveled Jade Glass Crescent Plaque Small](https://admin.shopify.com/store/trophy-partner/products/15512208474223) | GM823C GM823D GM823E | Beveled Jade Glass Crescent Plaque | 3 |
| [Black Diamond Carve Lucite Award](https://admin.shopify.com/store/trophy-partner/products/15512207687791) | CD1103A CD1103B | Black Diamond Carve, Med; Black Diamond Carve, Small | 2 |
| [Blue Award with Clear Lucite Base](https://admin.shopify.com/store/trophy-partner/products/15512265162863) | C0613 C0614 C0610 C0611 C0612 | Enterprise Large Blue Dynasty Award; Large Blue Dynasty Award; Large Blue Dynasty Award with Clear Lucite Base; Small Blue Dynasty Award; Small Blue Dynasty Award with Clear Lucite Base | 5 |
| [Bold Clear Crystal Facet Oval](https://admin.shopify.com/store/trophy-partner/products/15512176361583) | GI647A GI647B GI647C | Bold Clear Crystal Facet Oval; Bold Clear Crystal Facet Oval, Med; Bold Clear Crystal Facet Oval, Small | 3 |
| [Bold Clear Crystal Wedge Award](https://admin.shopify.com/store/trophy-partner/products/15512263196783) | GI635A GI635B | Bold Clear Crystal Wedge - Large; Bold Clear Crystal Wedge - Medium | 2 |
| [Canvas Portfolio with Leatherette Accent](https://admin.shopify.com/store/trophy-partner/products/15512176197743) | CM720A CM720B | Canvase Portfolio with Leatherette Accent | 11 |
| [Carved Flame with Blue Accent Award](https://admin.shopify.com/store/trophy-partner/products/15512206508143) | GM822A GM822B | Carved Flame with Blue Accent | 2 |
| [Cascade Metal Stars on Italian Marble Base](https://admin.shopify.com/store/trophy-partner/products/15512262934639) | CD476 CD477 CD478 | Cascade Metal Stars on Genuine Italian Marble Base - Large; Cascade Metal Stars on Genuine Italian Marble Base - Medium; Cascade Metal Stars on Genuine Italian Marble Base - Small | 9 |
| [Certificate/Overlay Plaque](https://admin.shopify.com/store/trophy-partner/products/15512214732911) | C3501A C3501B C3501C | Certificate/Overlay Plaque for 7" x 5" Insert; Certificate/Overlay Plaque for 8" x 6" Insert; Certificate/Overlay Plaque for 8½" x 11" Insert | 12 |
| [Choice of Digi-Color Plate on Economy Board](https://admin.shopify.com/store/trophy-partner/products/15512214503535) | DCC3001C* DCC3001D* DCC3001E* | Choice of Digi-Color Plate on Economy Board | 6 |
| [Clear Beveled Arrowhead Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512260935791) | GM716A GM716B GM716C | Clear Beveled Glass Arrowhead on Base - Large; Clear Beveled Glass Arrowhead on Base - Medium; Clear Glass Arrowhead on Base,  | 3 |
| [Clear Center Crystal with Color Accent](https://admin.shopify.com/store/trophy-partner/products/15512193040495) | GM809A* GM809B* GM809C* | Blue  Accent Clear Center Crystal; Red Accent Clear Center Crystal | 6 |
| [Clear Crystal Star Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512260542575) | GM711A GM711B GM711C | Clear Crystal Star on Base - Large; Clear Crystal Star on Base - Medium; Clear Crystal Star on Base - Small | 3 |
| [Clear Crystal Wave Small](https://admin.shopify.com/store/trophy-partner/products/15512205328495) | GM817B GM817C | Clear Crystal Wave | 2 |
| [Clear Monolith with Blue Insert](https://admin.shopify.com/store/trophy-partner/products/15512204869743) | GM748A GM748B | Clear Monolith with Blue Insert, Large; Clear Monolith with Blue Insert, Small | 2 |
| [Clear Star Award with Blue Triangle Base](https://admin.shopify.com/store/trophy-partner/products/15512204836975) | GM746A GM746B | Clear Star with Blue Triangle Base, Large; Clear Star with Blue Triangle Base, Small | 2 |
| [Clear Swoop  with Black & Clear Base Large](https://admin.shopify.com/store/trophy-partner/products/15512204705903) | GM811A GM811B | Clear Swoop  with Black & Clear Base | 2 |
| [Clear Swoop with Black Accent](https://admin.shopify.com/store/trophy-partner/products/15512204607599) | GM812A GM812B GM812C | Clear Swoop with Black Accent | 3 |
| [Cobalt and Clear Optic Crystal Ribbon Award](https://admin.shopify.com/store/trophy-partner/products/15512259395695) | GM714A GM714B GM714C | Cobalt and Clear Optic Crystal Ribbon - Lrg; Cobalt and Clear Optic Crystal Ribbon - Med; Cobalt and Clear Optic Crystal Ribbon - Small | 3 |
| [Cosmopolitan Plaque Large](https://admin.shopify.com/store/trophy-partner/products/15512203952239) | CD1096* CD1097* | Cosmopolitan Plaque, 8x15; Cosmopolitan Plaque, 9x12 | 7 |
| [Crossroads Horizontal Award](https://admin.shopify.com/store/trophy-partner/products/15512175181935) | CD1040A CD1040B | Crossroads Award, Horizontal Medium; Crossroads Award, Horizontal Small | 8 |
| [Crystal Column Sports Award with Ball](https://admin.shopify.com/store/trophy-partner/products/15512203788399) | GM833B GM833L GM833M GM833A GM833D GM833H GM833J GM833G GM833K GM833C GM833I GM833E | Crystal Column with Ball, Female Basketball; Crystal Column with Ball, Female Golf; Crystal Column with Ball, Female Soccer; Crystal Column with Ball, Female Softball; Crystal Column with Ball, Female | 12 |
| [Crystal Octagon](https://admin.shopify.com/store/trophy-partner/products/15512203329647) | GM756A GM756B GM756C | Crystal Octagon, Lrg; Crystal Octagon, Med; Crystal Octagon, Small | 3 |
| [Crystal Reaching Arch Award on Glass Base](https://admin.shopify.com/store/trophy-partner/products/15512257953903) | GI524A GI524B | Optic Crystal Reaching Arch on Black Glass Base - Large; Optic Crystal Reaching Arch on Black Glass Base - Small | 2 |
| [Crystal Sports Tower](https://admin.shopify.com/store/trophy-partner/products/15512202838127) | GM832G GM832E GM832Q GM832C GM832L GM832J GM832K GM806C GM832M GM806D GM832I GM832D GM832N GM832A GM832H GM832O GM832P GM806E | Crystal Tower   ; Crystal Tower, Cheerleader; Crystal Tower, Female Basketball; Crystal Tower, Female Golf; Crystal Tower, Female Soccer; Crystal Tower, Female Tennis; Crystal Tower, Female Volleyball | 19 |
| [Crystal Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512202772591) | GM806A GM806B | Crystal Tower    | 2 |
| [Crystal with Metal Stars Topped Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512257036399) | GM562B GM562C | Silver Star Topped Optic Crystal Tower - Large; Silver Star Topped Optic Crystal Tower - Medium | 2 |
| [Deep Beveled Back On Base](https://admin.shopify.com/store/trophy-partner/products/15512256708719) | GM411A GM411B GM411C | Deep Beveled Back on Base - Large; Deep Beveled Back on Base - Medium; Deep Beveled Back on Base - Small | 3 |
| [Diamond Bevel Glass and Marble Pillar Base Award](https://admin.shopify.com/store/trophy-partner/products/15512256610415) | GM726A GM726B | Diamond Bevel Glass and Marble Pillar Base - Large; Diamond Bevel Glass and Marble Pillar Base - Small | 2 |
| [Diamond Columns](https://admin.shopify.com/store/trophy-partner/products/15512202477679) | GM818A GM818B GM818C | Diamond Columns | 3 |
| [Diamond Glass Award On Chrome Base](https://admin.shopify.com/store/trophy-partner/products/15512257134703) | GI27A GI27B | Starphire Glass with Brushed Silver Base- Large; Starphire Glass with Brushed Silver Base- Medium | 2 |
| [Digi-Color Lucite Award](https://admin.shopify.com/store/trophy-partner/products/15512256905327) | DCCD424** DCCD424B DCCD425** | Large Digi-Color Lucite Award; Medium Digi-Color Lucite Award | 6 |
| [Ebony Finish 12-Plt Layered Blue and Silver Border Plaque with Easy Perpetual Plate Release Program](https://admin.shopify.com/store/trophy-partner/products/15512168333423) | EP9 EP9PK | Ebony Finish 12-Plt Layered Blue and Silver Border Plaque  with Easy Perpetual Plate Release Program; Ebony Finish 12-Plt Layered Blue and Silver Border Plaque  with Easy Perpetual Plate Release Progr | 2 |
| [Ebony Plaque with Raised Starphire Glass](https://admin.shopify.com/store/trophy-partner/products/15512255987823) | G0655A G0655B | Ebony Piano Finish Board with Raised Starphire Glass | 2 |
| [Etched Premium Standing Jade Tablet Large](https://admin.shopify.com/store/trophy-partner/products/15512255660143) | GM446A GM446B | Premium Standing Jade Glass Tablet - Medium; Premium Standing Jade Glass Tablet - Small | 2 |
| [Faceted Circle Wedge on Slant Side Base Large](https://admin.shopify.com/store/trophy-partner/products/15512201592943) | GM815A GM815B | Faceted Circle Wedge on Slant Side Base | 2 |
| [Faceted Edge Circle Award on Slant Base](https://admin.shopify.com/store/trophy-partner/products/15512201265263) | GM814A GM814B GM814C | Faceted Edge Circle on Slant Base | 3 |
| [Florence Series Faceted Crystal Circle Award](https://admin.shopify.com/store/trophy-partner/products/15512255365231) | GM615A GM615B GM615C | Faceted Crystal Circle - Large; Faceted Crystal Circle - Medium; Faceted Crystal Circle - Small | 3 |
| [Florentine Edge Plate Plaque](https://admin.shopify.com/store/trophy-partner/products/15512214405231) | C071A C071B | Florentine Gold Edge Plate on Ebony Board | 8 |
| [Freestanding Lucite Block](https://admin.shopify.com/store/trophy-partner/products/15512254939247) | C0813A C0813B | Freestanding Lucite Block | 2 |
| [Glass Droplets Indigo Stream Art Glass Award](https://admin.shopify.com/store/trophy-partner/products/15512234426479) | GM435B GM435C | Indigo Stream Art Glass - Large (Includes Silver Color-Fill on Base Only); Indigo Stream Art Glass - Medium (Includes Silver Color-Fill on Base Only) | 2 |
| [Glass Shield w/ Diamond On Base](https://admin.shopify.com/store/trophy-partner/products/15512234295407) | GM617A GM617B GM617C | Reflective Glass Shield with Diamond  on Black Glass Base - Large; Reflective Glass Shield with Diamond  on Black Glass Base - Medium; Reflective Glass Shield with Diamond  on Black Glass Base - Small | 3 |
| [Grooved Brilliance Plaque](https://admin.shopify.com/store/trophy-partner/products/15512231837807) | CD959G CD959* | Grooved Brilliance Plaque | 2 |
| [Hi-Tech Lucite Riser Plaque with Wood Backing and Plate](https://admin.shopify.com/store/trophy-partner/products/15512233803887) | C6801* C6802* | Hi-Tech Lucite Riser Plaque with Wood Backing and Plate | 4 |
| [Interlocking Perpetual Award Bars](https://admin.shopify.com/store/trophy-partner/products/15512161124463) | CD908RL CD908RR | Rectangle Interlocking Bar, Left; Rectangle Interlocking Bar, Right | 4 |
| [Jade Faceted Circle Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512231575663) | GM663A GM663B GM663C | Pearl Edge Circle Clear - Large; Pearl Edge Circle Clear - Medium; Pearl Edge Circle Clear - Small | 3 |
| [Jade Faceted Flame Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512231510127) | GM662A GM662B GM662C | Pearl Edge Flame Clear - Large; Pearl Edge Flame Clear - Medium; Pearl Edge Flame Clear - Small | 3 |
| [Jade Glass Circle On Base](https://admin.shopify.com/store/trophy-partner/products/15512231444591) | GI496A GI496B | Jade Glass Circle on Base - Large; Jade Glass Circle on Base - Medium | 2 |
| [Jade Glass Slant Peak On Base Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512230887535) | GI527A GI527B GI527C | Jade Glass Slant Peak on Base - Large; Jade Glass Slant Peak on Base - Medium; Jade Glass Slant Peak on Base - Small | 3 |
| [Jade Glass Star Crescent Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512230789231) | GM668A GM668B | Jade Glass Star Crescent | 2 |

… 38 más en el CSV.

### 3.4 No se pueden sincronizar

| producto | ids (tipo) |
|---|---|
| [1 Year Anniversary Service Award Freestanding](https://admin.shopify.com/store/trophy-partner/products/15512213028975) | `CD929AY1G`=ORPHAN `CD929AY1S`=ORPHAN |
| [1-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512212701295) | `CD1235AY1`=ORPHAN `CD1236AY1B`=ORPHAN `CD1236AY1G`=ORPHAN `CD1236AY1R`=ORPHAN |
| [10 Year Anniversary Freestanding Service Award](https://admin.shopify.com/store/trophy-partner/products/15512212996207) | `CD929BY10G`=ORPHAN `CD929BY10S`=ORPHAN |
| [10-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512212963439) | `CD1235BY10`=ORPHAN `CD1236BY10B`=ORPHAN `CD1236BY10G`=ORPHAN `CD1236BY10R`=ORPHAN |
| [11 oz. Black Coffee Mug](https://admin.shopify.com/store/trophy-partner/products/15512271061103) | `CM701-02`=ORPHAN `CM701-05`=ORPHAN `CM701-89`=ORPHAN |
| [11 oz. Black Mug](https://admin.shopify.com/store/trophy-partner/products/15512271519855) | `CM700-05`=ORPHAN |
| [11 oz. Blue Mug](https://admin.shopify.com/store/trophy-partner/products/15512271257711) | `CM700-14`=ORPHAN |
| [11 oz. Green Mug](https://admin.shopify.com/store/trophy-partner/products/15512271159407) | `CM700-67`=ORPHAN |
| [11 oz. Grey Mug](https://admin.shopify.com/store/trophy-partner/products/15512271093871) | `CM700-08`=ORPHAN |
| [14 oz. Ceramic Mug](https://admin.shopify.com/store/trophy-partner/products/15512270995567) | `CM702-06`=ORPHAN |
| [15 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512212897903) | `CD929BY15G`=ORPHAN `CD929BY15S`=ORPHAN |
| [15-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512212799599) | `CD1235BY15`=ORPHAN `CD1236BY15B`=ORPHAN `CD1236BY15G`=ORPHAN `CD1236BY15R`=ORPHAN |
| [16 oz. Pilsner Glass](https://admin.shopify.com/store/trophy-partner/products/15512181506159) | `G0993`=ORPHAN |
| [2 Year Anniversary Freestanding](https://admin.shopify.com/store/trophy-partner/products/15512212635759) | `CD929AY2G`=ORPHAN `CD929AY2S`=ORPHAN |
| [2-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512212275311) | `CD1235AY2`=ORPHAN `CD1236AY2B`=ORPHAN `CD1236AY2G`=ORPHAN `CD1236AY2R`=ORPHAN |
| [20 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512212570223) | `CD929BY20G`=ORPHAN `CD929BY20S`=ORPHAN |
| [20-Year Anniversary Freestanding Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512212537455) | `CD1235BY20`=ORPHAN `CD1236BY20B`=ORPHAN `CD1236BY20G`=ORPHAN `CD1236BY20R`=ORPHAN |
| [25 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512212471919) | `CD929BY25G`=ORPHAN `CD929BY25S`=ORPHAN |
| [25-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512212308079) | `CD1235BY25`=ORPHAN `CD1236BY25B`=ORPHAN `CD1236BY25G`=ORPHAN `CD1236BY25R`=ORPHAN |
| [3 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512212177007) | `CD929AY3G`=ORPHAN `CD929AY3S`=ORPHAN |
| [3-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512211652719) | `CD1235AY3`=ORPHAN `CD1236AY3B`=ORPHAN `CD1236AY3G`=ORPHAN `CD1236AY3R`=ORPHAN |
| [30 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512212078703) | `CD929BY30G`=ORPHAN `CD929BY30S`=ORPHAN |
| [30-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512211947631) | `CD1235BY30`=ORPHAN `CD1236BY30B`=ORPHAN `CD1236BY30G`=ORPHAN `CD1236BY30R`=ORPHAN |
| [35 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512211849327) | `CD929BY35G`=ORPHAN `CD929BY35S`=ORPHAN |
| [35-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512211718255) | `CD1235BY35`=ORPHAN `CD1236BY35B`=ORPHAN `CD1236BY35G`=ORPHAN `CD1236BY35R`=ORPHAN |
| [3D Etched Crystal Cube](https://admin.shopify.com/store/trophy-partner/products/15512270536815) | `GNS137`=ORPHAN |
| [3D Etched Crystal Diamond Cube](https://admin.shopify.com/store/trophy-partner/products/15512270471279) | `GNS142`=ORPHAN |
| [4 Year Anniversary Freestanding](https://admin.shopify.com/store/trophy-partner/products/15512211587183) | `CD929AY4G`=ORPHAN `CD929AY4S`=ORPHAN |
| [4-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512211292271) | `CD1235AY4`=ORPHAN `CD1236AY4B`=ORPHAN `CD1236AY4G`=ORPHAN `CD1236AY4R`=ORPHAN |
| [40 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512211521647) | `CD929BY40G`=ORPHAN `CD929BY40S`=ORPHAN |
| [40-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512211456111) | `CD1235BY40`=ORPHAN `CD1236BY40B`=ORPHAN `CD1236BY40G`=ORPHAN `CD1236BY40R`=ORPHAN |
| [45 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512211357807) | `CD929BY45G`=ORPHAN `CD929BY45S`=ORPHAN |
| [45-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512211325039) | `CD1235BY45`=ORPHAN `CD1236BY45B`=ORPHAN `CD1236BY45G`=ORPHAN `CD1236BY45R`=ORPHAN |
| [5 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512211259503) | `CD929AY5G`=ORPHAN `CD929AY5S`=ORPHAN |
| [5-Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512211030127) | `CD1235AY5`=ORPHAN `CD1236AY5B`=ORPHAN `CD1236AY5G`=ORPHAN `CD1236AY5R`=ORPHAN |
| [50 Year Anniversary Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512211226735) | `CD929BY50G`=ORPHAN `CD929BY50S`=ORPHAN |
| [50-Year Anniversary Freestanding Trophy](https://admin.shopify.com/store/trophy-partner/products/15512211095663) | `CD1235BY50`=ORPHAN `CD1236BY50B`=ORPHAN `CD1236BY50G`=ORPHAN `CD1236BY50R`=ORPHAN |
| [A Frame Acrylic Phone Holder Calendar](https://admin.shopify.com/store/trophy-partner/products/15512210899055) | `PH15`=ORPHAN |
| [A-Frame Phone Holder](https://admin.shopify.com/store/trophy-partner/products/15512210735215) | `PH01`=ORPHAN `PH03A`=ORPHAN `PH03B`=ORPHAN |
| [Abstract Acrylic Award with Wood Accent](https://admin.shopify.com/store/trophy-partner/products/15512180916335) | `CD1254BL`=ORPHAN `CD1254GR`=ORPHAN `CD1254RD`=ORPHAN |

… 212 más en el CSV.

### 3.5 Avisos en productos que sí se sincronizan

| aviso | productos |
|---|---|
| ids muertos en `ps_product_ids` | 23 |
| lista ids de variante de otro producto | 9 |
| el id canónico no sirve (arranca otro id de la lista) | 6 |

### 3.6 Una sync se tragaría a productos hermanos

El Inventory de PaceSetter responde por familia entera, y `ForeignProductSync.union` adopta cada miembro de la familia que es producto propio. Está bien cuando el producto de la tienda ES la familia (portafolio CM297); está mal cuando la tienda vende cada color o estado como producto propio: sincronizar uno lo convierte en la familia entera y añade los ids de los hermanos a su `ps_product_ids`. **0 productos** harían esto; no deben sincronizarse hasta que la app no adopte ids que ya reclama otro producto.

| línea PaceSetter | productos | ≈ variantes cada uno tras la sync | p. ej. |
|---|---|---|---|

### 4. Productos PaceSetter que ningún producto de la tienda reclama

Ninguno se crea: con `sync.create-products.enabled=false` una importación de un id que no está en la tienda se rechaza con un 409.

| veredicto | ids | significado |
|---|---|---|
| ABSORBED | 0 | la sync de un producto de la tienda lo añadirá como variante |
| DUPLICATE_RISK | 13 | sus variantes ya están en un producto de la tienda con otros ids — es el id al que deberían apuntar (3.2) |
| NEW | 139 | no está en la tienda de ninguna forma — se ignora |
| NOT_IMPORTABLE | 154 | sin precio o sin ficha |

| id PaceSetter | nombre | ya está en |
|---|---|---|
| `CM710*` | Polar Camel 30 oz. Ringneck Tumbler | p-6168-polar-camel-ringneck-tumblers-personalized |
| `CM713*` | Polar Camel 16 oz. Stemless Tumbler | p-8848-polar-camel-small, p-8850-polar-camel-medium, p-8850-polar-camel-medium, p-8848-polar-camel-small |
| `DCC3001BEB` | Choice of Digi-Color Plate on Economy Board | p-8730-choice-of-digi-large, p-8730-choice-of-digi-large |
| `CM726*` | Polar Camel 15 oz. Mug, Black                                              | p-8848-polar-camel-small, p-8848-polar-camel-small |
| `CM727*` | Polar Camel Powder Coated Insulated Can Holder, 12/16 oz. can  | p-8847-polar-camel-stainless-steel-beverage-holder-large, p-8846-polar-camel-stainless-steel-beverage-holder-small, p-8847-polar-camel-stainless-steel-beverage-holder-large, p-8846-polar-camel-stainless-steel-beverage-holder-small |
| `CM731` | Polar Camel Powder Coated Insulated Travel Water Bottle Includes Straw | p-8848-polar-camel-small, p-8850-polar-camel-medium, p-8850-polar-camel-medium, p-8848-polar-camel-small |
| `C609LB` | Small Chisel Carve Tower on Base | p-5479-blue-chisel-carved-tower-award-on-base, p-8760-gold-chisel-carved-tower-on-base-large, p-8760-gold-chisel-carved-tower-on-base-large, p-5479-blue-chisel-carved-tower-award-on-base |
| `C610LB` | Medium Chisel Carve Tower on Base | p-5883-gold-chisel-carved-tower-on-base-award, p-5479-blue-chisel-carved-tower-award-on-base, p-5883-gold-chisel-carved-tower-on-base-award, p-5479-blue-chisel-carved-tower-award-on-base |
| `C701BL` | Chisel Carve Tower on Base | p-5479-blue-chisel-carved-tower-award-on-base, p-6182-purple-chisel-carve-tower-award-on-base, p-5430-black-chisel-carve-tower-award-on-base, p-5908-green-chisel-carve-tower-award-on-base |
| `C701L` | Chisel Carve Tower | p-5882-gold-chisel-carve-tower-award, p-6208-red-spectrum-chisel-carved-tower-award, p-8891-teal-chisel-carve-tower-award, p-8147-purple-chisel-carve-tower-award |
| `CD547B` | Large Digi-Color Direct Plaque | p-8775-large-digi-large, p-6326-walnut-finish-digi, p-5696-digi-color-mahogany-plaque, p-8775-large-digi-large |
| `CD547C` | Extra-Large Digi-Color Direct Plaque | p-7985-extra-large-digi, p-5696-digi-color-mahogany-plaque, p-6322-walnut-finish-digi, p-7985-extra-large-digi |
| `CM715` | Gold Finish Plastic Cup on Marble Base | p-8052-gold-finish-plastic-cup-on-marble-base, p-8052-gold-finish-plastic-cup-on-marble-base |

### 5. Duplicados dentro de la tienda

| comprobación | cantidad |
|---|---|
| `migration.legacy_sku` en más de un producto | 0 |
| mismo título en más de un producto PromoStandards | 28 |
| productos sin tag con SKU `PS…` y sin ids (invisibles para la app) | 5 |

| título | productos |
|---|---|
| 3/8" thick lucite wall plaque with custom digi-color | 2 |
| beveled lucite fin on marble base | 2 |
| circular glass on a polished silver and black glass deco base | 2 |
| clear acrylic circle on clear base | 3 |
| crystal crossed-golf clubs on black base (includes sandblast and silver color-fill on base) | 3 |
| crystal driver on tall base | 2 |
| crystal flame set in polished metal on a black crystal base | 2 |
| crystal golf driver head | 2 |
| crystal tapered monolith | 2 |
| freestanding lucite block | 2 |
| full-color certificate printing | 5 |
| gentle waves clear chisel tower award | 2 |
| golf ball tower | 2 |
| leatherette beverage holder | 2 |
| leatherette bottle opener keychain | 2 |
| leatherette cigar case with cutter | 2 |
| leatherette flask | 2 |
| leatherette hard business card case | 2 |
| leatherette journal | 2 |
| leatherette pen | 2 |
| leatherette phone wallet with ring | 2 |
| leatherette picture frame | 2 |
| leatherette portfolio | 2 |
| leatherette round coaster | 2 |
| stackable clear acrylic block | 3 |
| vivid spiral acrylic on marble | 2 |
| walnut finish plaque with black florentine | 2 |
| ¼" thick lucite wall plaque with custom digi-color | 3 |

## Ficheros

- `store_products.csv` — una fila por producto de la tienda: veredicto, id que arranca, tipo de cada id, líneas y nombres en PaceSetter, variantes esperadas, lo que añade la unión.
- `supplier_ids.csv` — una fila por id reclamado: tipo, línea, de qué producto es variante, quién lo reclama.
- `shared_variants.csv` — cada variante PaceSetter que acabaría en más de un producto tras la sync.
- `unclaimed_sellable.csv` — productos PaceSetter que ningún producto de la tienda reclama.
- `supplier_cache.json` — las respuestas del proveedor; una nueva ejecución las reutiliza (`--retry-errors` para volver a pedir las que fallaron).
