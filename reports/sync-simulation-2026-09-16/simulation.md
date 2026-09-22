# Simulación de sync — tienda vs PaceSetter (2026-09-16 18:58)

Simulación de solo lectura de `POST /api/sync/products/{id}` sobre todos los productos PromoStandards de `trophy-partner`. No se escribió nada. Generado por `tools/simulate_store_sync.py`; el detalle fila a fila está en los CSV junto a este fichero. La app **no crea productos** (`sync.create-products.enabled=false`): sólo actualiza los que ya están en Shopify.

## 0. Productos sanos — se pueden migrar ya, sólo a nivel de variantes

Sano = todo a la vez: está en Shopify (no se crea nada); todos sus ids son productos vivos de PaceSetter; todos son de **una sola familia** de PaceSetter y ningún otro producto de la tienda tiene ids de esa familia; y ninguna variante que traería la sync está en la lista de otro producto ni acabaría en otro.

**262 productos sanos**, que pasarían de 457 variantes a ≈457. 0 de ellos completarían su familia con ids que su lista no tenía (variantes reales de esa misma familia que no reclama ningún otro producto).

Lista: `healthy_products.txt`, un id por línea, para `python tools/sync_store_catalog.py --only @<ruta>/healthy_products.txt --report <run>.jsonl`.

| por qué no es sano (un producto puede tener varios motivos) | productos | sólo por este motivo |
|---|---|---|
| ids que no son un producto vivo | 548 | 185 |
| familia compartida con otro producto | 378 | 1 |
| varias familias | 243 | 82 |
| variantes que lista otro producto | 131 | 0 |
| variantes que acabarían en otro producto | 125 | 0 |
| sin tag o sin ids | 3 | 3 |

| producto sano | ids | familia PaceSetter | variantes hoy → tras la sync |
|---|---|---|---|
| [14 oz. Pilsner Tumbler](https://admin.shopify.com/store/trophy-partner/products/15512178589807) | CM717* | CM717* | 16 → 16 |
| [15 oz. Stemless Wine Glass](https://admin.shopify.com/store/trophy-partner/products/15512181604463) | G0991 | G0991 | 1 → 1 |
| [16 oz. Pint Mixing Glass](https://admin.shopify.com/store/trophy-partner/products/15512181440623) | G0992 | G0992 | 1 → 1 |
| [16 oz. Willi Becher Beer Glass](https://admin.shopify.com/store/trophy-partner/products/15512181375087) | G0994 | G0994 | 1 → 1 |
| [19 oz. Wine Glass](https://admin.shopify.com/store/trophy-partner/products/15512181276783) | G0996 | G0996 | 1 → 1 |
| [20 oz. Pilsner Tumbler](https://admin.shopify.com/store/trophy-partner/products/15512178524271) | CM716* | CM716* | 16 → 16 |
| [3/8” Thick Custom Digi Small](https://admin.shopify.com/store/trophy-partner/products/15512212144239) | CD267C | CD267C | 1 → 1 |
| [8 oz. Champagne Flute](https://admin.shopify.com/store/trophy-partner/products/15512181211247) | G0995 | G0995 | 1 → 1 |
| [9 oz. Stemless Wine Glass](https://admin.shopify.com/store/trophy-partner/products/15512181080175) | G0990 | G0990 | 1 → 1 |
| [Acacia Wood/Slate Cheese Set](https://admin.shopify.com/store/trophy-partner/products/15512210833519) | CM751 | CM751 | 1 → 1 |
| [Acrylic Awareness Ribbon Awards](https://admin.shopify.com/store/trophy-partner/products/15512160960623) | CD1268 | CD1268 | 1 → 1 |
| [Acrylic Challenge Coin Stand](https://admin.shopify.com/store/trophy-partner/products/15512210800751) | CD982 | CD982 | 1 → 1 |
| [Alder Cube Large](https://admin.shopify.com/store/trophy-partner/products/15512210538607) | CM755B | CM755B | 1 → 1 |
| [Alder Cube Small](https://admin.shopify.com/store/trophy-partner/products/15512210604143) | CM755A | CM755A | 1 → 1 |
| [Alder Wood Oval Name Bar](https://admin.shopify.com/store/trophy-partner/products/15512269815919) | CM464 | CM464 | 1 → 1 |
| [Alder Wood Rectangle Name Bar](https://admin.shopify.com/store/trophy-partner/products/15512269619311) | CM465 | CM465 | 1 → 1 |
| [Angular Perpetual Award](https://admin.shopify.com/store/trophy-partner/products/15512210112623) | GI668 | GI668 | 1 → 1 |
| [Anniversary Achievement Years of Service Awards](https://admin.shopify.com/store/trophy-partner/products/15512269488239) | CD903Y_ | CD903Y_ | 17 → 17 |
| [Anniversary Service Recognition Awards](https://admin.shopify.com/store/trophy-partner/products/15512210014319) | CD981Y* | CD981Y* | 12 → 12 |
| [Anniversary Years of Service Achievement Plaque](https://admin.shopify.com/store/trophy-partner/products/15512269357167) | CD902Y* | CD902Y* | 14 → 14 |
| [Anniversary Years of Service Freestanding Award](https://admin.shopify.com/store/trophy-partner/products/15512269193327) | CD904BY* | CD904BY* | 9 → 9 |
| [Aquus Series Lucite Peak Award](https://admin.shopify.com/store/trophy-partner/products/15512268865647) | CDUS02C | CDUS02C | 1 → 1 |
| [Art Glass Award on Glass Base with Plate](https://admin.shopify.com/store/trophy-partner/products/15512268734575) | GM577 | GM577 | 1 → 1 |
| [Art Glass Black & Gold Coral Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512268472431) | GM639 | GM639 | 1 → 1 |
| [Art Glass Black & Gold Curve on Base Award](https://admin.shopify.com/store/trophy-partner/products/15512268046447) | GM640 | GM640 | 1 → 1 |
| [Art Glass Blue & Gold Drop On Base](https://admin.shopify.com/store/trophy-partner/products/15512268013679) | GM637 | GM637 | 1 → 1 |
| [Art Glass Green And Gold Drop On Base](https://admin.shopify.com/store/trophy-partner/products/15512267849839) | GM638 | GM638 | 1 → 1 |
| [Art Glass Green Spiral on Black Glass Base Award](https://admin.shopify.com/store/trophy-partner/products/15512267751535) | GI206 | GI206 | 1 → 1 |
| [Ball Alder Ornament](https://admin.shopify.com/store/trophy-partner/products/15512209850479) | CM757A | CM757A | 2 → 2 |
| [Bamboo BBQ Fork](https://admin.shopify.com/store/trophy-partner/products/15512209817711) | CM752B | CM752B | 1 → 1 |
| [Bamboo BBQ Set](https://admin.shopify.com/store/trophy-partner/products/15512267325551) | CM416 | CM416 | 1 → 1 |
| [Bamboo BBQ Spatula](https://admin.shopify.com/store/trophy-partner/products/15512209719407) | CM752C | CM752C | 1 → 1 |
| [Bamboo BBQ Spatula & Bottle Openers](https://admin.shopify.com/store/trophy-partner/products/15512209784943) | CM752A | CM752A | 1 → 1 |
| [Bamboo BBQ Tongs](https://admin.shopify.com/store/trophy-partner/products/15512209653871) | CM752D | CM752D | 1 → 1 |
| [Bamboo Cheese Cutting Board](https://admin.shopify.com/store/trophy-partner/products/15512267292783) | CM420 | CM420 | 1 → 1 |
| [Bamboo Wine Presentation Box with Tools](https://admin.shopify.com/store/trophy-partner/products/15512267161711) | CM460 | CM460 | 1 → 1 |
| [Barrel Stave Bottle Opener](https://admin.shopify.com/store/trophy-partner/products/15512209457263) | CM748 | CM748 | 1 → 1 |
| [Bevel Edge Clear Crystal Paperweight](https://admin.shopify.com/store/trophy-partner/products/15512209162351) | GM824 | GM824 | 1 → 1 |
| [Beveled Crystal Circle Award](https://admin.shopify.com/store/trophy-partner/products/15512208769135) | GM757A | GM757A | 1 → 1 |
| [Beveled Crystal w/ Gold Glass Accent](https://admin.shopify.com/store/trophy-partner/products/15512266834031) | GI215 | GI215 | 1 → 1 |

… 222 más en el CSV.

## Respuestas directas

### 1. ¿Cuántos productos de Shopify están disponibles en PaceSetter? (1022 productos)

| situación de sus ids | productos | qué hace la sync |
|---|---|---|
| todos existen como producto en PaceSetter | 471 | se sincroniza completo |
| algunos sí, otros no | 22 | se sincroniza con los que existen; los demás se ignoran |
| ninguno es producto, pero son **variantes** de un producto PaceSetter | 307 | no se sincroniza hasta que su lista apunte al producto (ver 3.2) |
| ninguno existe en PaceSetter | 222 | no se sincroniza |

**Disponibles: 493 de 1022.** Otros 307 lo serían si se corrige su lista de ids.

### 2. ¿Dos o más productos de Shopify reclaman lo mismo?

| nivel | casos | productos de Shopify implicados |
|---|---|---|
| el **mismo id** en la lista de dos productos | 0 | 0 |
| el **mismo producto PaceSetter** (por su id o por sus variantes) | 103 | 237 |
| la **misma variante PaceSetter** en dos productos **después** de sincronizar | 186 | 125 |

- **Mismo id: 0.** Ningún id aparece en dos productos, así que el índice de la app siempre resuelve a un único producto.
- **Mismo producto PaceSetter: 103.** La tienda reparte un producto PaceSetter entre varios productos de Shopify, casi siempre uno por color o por medida, cada uno con el id de su variante (p. ej. las Halley Plaque Small/Medium/Large × Red/Gold/Green son todas variantes de `C750`). Hoy no chocan porque cada uno lista un id distinto, pero ninguno se sincroniza así (ver 3.2).
- **Misma variante tras la sync: 186 variantes en 125 productos.** Aquí sí habría duplicados: al sincronizar, la app añade las variantes que el Inventory de PaceSetter devuelve para toda la familia, y donde la tienda vende cada color o estado como producto propio, cada uno acabaría con las variantes de todos los demás (ver 3.6). Hay además 14 ids de variante que PaceSetter declara en varios productos a la vez; no se atribuyen a ninguno.

| producto PaceSetter | nombre | productos de Shopify que lo reclaman |
|---|---|---|
| `C750` |  | 10: Blue Halley Collection Plaque; Large Gold Halley Plaque; Large Green Halley Plaque; Large Red Halley Plaque (+6) |
| `CM291*` | Leatherette Oval Keychain | 9: Cork Leatherette Oval Keychain; Leatherette Oval Keychain; Black/Silver Leatherette Oval Keychain; Dark Brown Leatherette Oval Keychain (+5) |
| `C701BL` | Chisel Carve Tower on Base | 7: Black Chisel Carve Tower Award on Base; Blue Chisel Carved Tower Award On Base; Gold Chisel Carved Tower on Base Award; Green Chisel Carve Tower Award on Base (+3) |
| `C701L` | Chisel Carve Tower | 7: Black Chisel Carve Tower Award on Base; Blue Chisel Carve Tower Award; Gold Chisel Carve Tower Award; Green Chisel Carve Tower Award (+3) |
| `C023` |  | 6: Blue Plaque w/ Marble Mist Small; Blue Plaque w/ Marble Mist Large; Red Plaque w/ Marble Mist Small; Red Plaque with Marble Mist Large (+2) |
| `CD414*` | Blue Marble Florentine Plate on Ebony Board | 6: Blue Marble Florentine Plate on Ebony Finish Board; Red Marble Florentine Plate on Ebony Board; Blue Marble Florentine Plate on Ebony Board; Green Marble Florentine Plate on Ebony Board (+2) |
| `GI638` | Clear Crystal Chisel Carve | 6: Chisel Carved with Reflective Black Bottom Award; Chisel Carved with Reflective Gold Bottom Award; Chisel Carved with Reflective Red Bottom Award; Clear Crystal Chisel Carve Award (+2) |
| `CM293` | 7-Piece Leatherette Manicure Gift Set | 5: 7-Piece Black/Silver Leatherette Manicure Gift Set; 7-Piece Dark Brown Leatherette Manicure Gift Set; 7-Piece Light Brown Leatherette Manicure Gift Set; 7-Piece Pink Leatherette Manicure Gift Set (+1) |
| `CM296` | Leatherette Phone Easel | 5: Black/Silver Leatherette Phone Easel; Dark Brown Leatherette Phone Easel; Grey Leatherette Phone Easel; Light Brown Leatherette Phone Easel (+1) |
| `C071A` | Florentine Gold Edge Plate on Ebony Board | 4: Florentine Edge Plate on Ebony Board Medium; Florentine Gold Edge Plate on Ebony Board; Walnut Finish Plaque with Black Florentine Plate; Walnut Finish Plaque with Black Florentine |
| `C3501A` | Certificate/Overlay Plaque for 7" x 5" Insert | 4: Ebony Finish Certificate/Overlay Plaque; Genuine Walnut Certificate Plaque; Walnut Finish Certificate/Overlay Plaque in Gift Box; Walnut Finish Certificate/Overlay Plaque in Mailer Box |
| `C612LB` |  | 4: Blue Chisel Carved Tower Award On Base; Gold Chisel Carved Tower on Base Award; Gold Spectrum Chisel Carved Tower Award; Blue Spectrum Chisel Carved Tower Small |
| `CD961` | Layered Acrylic Plaque with Color Back and  Circle-Cutout Silver Plate (includes imprint in 2 locations) | 4: Layered Acrylic Plaque w/Black Back and Circle; Layered Acrylic Plaque with Orange Back and Circle Award; Layered Acrylic Plaque w/Blue Back and Circle; Layered Acrylic Plaque w/Green and Circle |
| `DCCD520` |  | 4: Ebony Finish Plaque with choice of plate and board; Ebony Piano Finish Plaque; Choice of Digi Small; Choice of Digi Large |
| `GI595` | Clear Glass with Bold Black Crystal Accent | 4: Clear Glass with Bold Black Crystal Accent; Clear Glass with Bold Red Crystal Accent; Clear Glass with Bold Blue Crystal Accent; Clear Glass with Bold Green Crystal Accent |
| `C071B` | Florentine Gold Edge Plate on Ebony Board | 3: Florentine Gold Edge Plate on Ebony Board Large; Walnut Finish Plaque with Black Florentine Plate; Walnut Finish Plaque with Black Florentine |
| `C072` |  | 3: Gold Plaque w/ Black with Gold Florentine Plate; Genuine Walnut Elliptical Plaque with Black/Silver Florentine Small; Genuine Walnut Elliptical Plaque with Black/Silver Florentine Large |
| `C3501C` | Certificate/Overlay Plaque for 8½" x 11" Insert | 3: Ebony Finish Certificate/Overlay Plaque; Walnut Finish Certificate/Overlay Plaque in Gift Box; Walnut Finish Certificate/Overlay Plaque in Mailer Box |
| `CD1101A` | Blue Acrylic Color Drops, Small | 3: Blue Acrylic Color Drops Award; Green Acrylic Color Drops Award; Red Acrylic Color Drops Award |
| `CD1101B` | Blue Acrylic Color Drops, Med | 3: Blue Acrylic Color Drops Award; Green Acrylic Color Drops Award; Red Acrylic Color Drops Award |
| `CD547A` |  | 3: Digi-color Mahogany Plaque; Medium Digi; Walnut Finish Digi |
| `CD547B` | Large Digi-Color Direct Plaque | 3: Digi-color Mahogany Plaque; Walnut Finish Digi; Large Digi Large |
| `CD547C` | Extra-Large Digi-Color Direct Plaque | 3: Digi-color Mahogany Plaque; Walnut Finish  Digi; Extra-Large Digi |
| `CD636` | Arched Brilliance Lucite Award on Clear Base  (Includes Laser in 2 Locations) | 3: Blue Arched Brilliance Award on Clear Base; Red Arched Brilliance Award on Clear Base; Green Arched Brilliance Award on Clear Base |
| `CD904AY*` | Anniversary Freestanding Single Digit Black 1 Year | 3: Anniversary Freestanding 5 Year Award; Anniversary Freestanding 3 Year Award; Anniversary Freestanding 1 Year Award |

… 78 más en el CSV.

| familia PaceSetter | productos que compartirían variantes tras la sync |
|---|---|
| CD900* | 52: Alaska State Silhouette Awards; Alabama State Silhouette Awards; Arkansas State Silhouette Awards (+49) |
| CD953* | 40: Frosted Acrylic AK State Cutout on Black Plaque; Frosted Acrylic AL State Cutout on Black Plaque; Frosted Acrylic AR State Cutout on Black Plaque (+37) |
| C1407L* | 3: Green Diamond Carved Octagon Plaque; Red Diamond Carve Lucite Octagon Plaque; Blue Diamond Carve Lucite Octagon Plaque |
| CM286* | 3: Cork Flask; Leatherette Flask; Leatherette Flask |
| CM289* | 3: Cork Beverage Holder; Leatherette Beverage Holder; Leatherette Beverage Holder Large |
| C6801* | 2: Lucite Riser Plaque with Choice Of Plate; Silver Lucite Riser Plaque |
| C6802* | 2: Lucite Riser Plaque with Choice Of Plate; Silver Lucite Riser Plaque |
| CA21AX* | 2: Clear Star Power Sculptured Lucite Award; Gold Star Power Sculptured Lucite Award |
| CA21BX* | 2: Clear Star Power Sculptured Lucite Award; Gold Star Power Sculptured Lucite Award |
| CA21CX* | 2: Clear Star Power Sculptured Lucite Large; Gold Star Power Sculptured Lucite Small |
| CD121* | 2: Slide-in Certificate Rosewood Plaque in Gift Box; Slide-in Certificate Rosewood Plaque in Mailer Box |
| CM288* | 2: Leatherette Wine Bag Small; Leatherette Wine Bag Large |
| CM415* | 2: Leatherette Journal; Leatherette Journal |
| CM721* | 2: Journal with Phone Pouch Small; Journal with Phone Pouch Large |
| CM722A | 2: Leatherette Frame  Holds Small; Leatherette Frame  Holds Large |
| DCC3001C* | 2: Walnut Finish Plaque w/ Choice Of Plate & Board; Choice of Digi Medium |
| DCCD424** | 2: Large Rectangular Custom Digi Color Lucite Award; Rectangular Digi Color Lucite Ribbon Award |
| GI586* | 2: Clear and Black Crescent Curved Glass Award Trophy; Two-Tone Clear and Blue Crescent Curved Glass (Includes Silver Color |
| GM809A* | 2: Red Accent Clear Center Crystal; Blue  Accent Clear Center Crystal |
| GM809B* | 2: Red Accent Clear Center Crystal; Blue  Accent Clear Center Crystal |

… 1 más en el CSV.

### 3. ¿De qué está hecha la lista de ids de cada producto?

Un id puede ser un **producto** de PaceSetter (tiene su propia ficha y precio) o sólo una **variante** (una parte que PaceSetter vende dentro de otro producto).

| composición de `ps_product_ids` | productos | ejemplo |
|---|---|---|
| 1 id, que es un producto PaceSetter | 373 | Acrylic Awareness Ribbon Awards; Two-Tone Clear and Blue Crescent Curved Glass (Includes Silver Color (+371) |
| 1 id, que es una variante de otro producto PaceSetter | 154 | Walnut Finish Plaque with Black Florentine; Teal Chisel Carve Tower Award (+152) |
| 1 id, que no existe | 123 | Water Bottle Carabiner Small; Turquoise and Stone Lucite 13 Plate Photo Plaque (+121) |
| **varios productos PaceSetter** de la misma familia (colores que PaceSetter vende por separado) | 18 | Zipper Leatherette Portfolio; Leatherette Wine Bag Small (+16) |
| **varios productos PaceSetter** de familias distintas (medidas, modelos) | 98 | Interlocking Perpetual Award Bars; Scroll Border Photo Plaque (+96) |
| un producto PaceSetter + variantes | 2 | Gold Finish Plastic Cup on Marble Base; Bamboo Cutting Board with Handle Cutout |
| un producto PaceSetter + ids que no existen | 8 | Crimson Border Beveled Lucite; Blue Steel Contoured Lucite (+6) |
| sólo variantes | 153 | Walnut Finish Plaque with Marble Mist Plate; Taper Edge Award (+151) |
| varios ids, ninguno existe | 93 | Water Drop Award; Water Bottle Carabiner Large (+91) |

**Productos cuya lista reúne varios productos PaceSetter: 116.** Es la agrupación N:1 de la migración: la sync los convierte en variantes de un único producto de Shopify. Los de familias distintas son los que conviene revisar a mano (la tabla 3.3 muestra los nombres).

## Detalle

### Estado de partida

|  | cantidad |
|---|---|
| Productos en la tienda | 1037 |
| … PromoStandards (tag o ids) | 1022 |
| … ya sincronizados por la app (`trophy_sync.last_sync_at`) | 346 |
| … con 1 / 2 / 3+ variantes hoy | 906 / 49 / 67 |
| Handles creados por la app (`ps-…`) | 0 |
| Ids de PaceSetter distintos que reclama la tienda | 2024 |
| Ids vendibles de PaceSetter (`getProductSellable`) | 1278 |
| … de ellos, familias con comodín (`CM717*`) | 55 |

### Qué son los ids de la tienda en PaceSetter

| tipo | ids | significado |
|---|---|---|
| PRODUCT | 752 | vendible, con ficha y tabla de precios — puede arrancar una sync |
| PRODUCT_UNLISTED | 0 | no está en la lista de vendibles pero responde ficha y precio |
| NO_PRICE | 8 | tiene ficha pero no precio — la sync lo rechaza (saldría a 0) |
| NO_PRODUCT_DATA | 1 | vendible, pero Product Data no tiene partes |
| PART | 797 | **no es un producto: es una variante de otro producto PaceSetter** |
| FAMILY_PART | 19 | cae bajo un código de familia con comodín, pero ninguna respuesta lo lista |
| ORPHAN | 447 | sin rastro — descatalogado o recodificado |

### Veredicto por producto

| veredicto | productos | qué pasa |
|---|---|---|
| OK | 396 | se sincroniza; todos sus ids vivos son una misma línea de PaceSetter |
| MULTI_PRODUCT | 97 | se sincroniza uniendo **varias líneas de PaceSetter** en un producto |
| COLLISION | 0 | un id está también en otro producto — **bloqueante** |
| UNSYNCABLE_PARTS | 307 | ningún id arranca, pero son variantes de un producto vivo |
| UNSYNCABLE | 219 | ningún id que PaceSetter sirva |
| NO_IDS | 3 | con tag pero sin `ps_product_id(s)` |

### 3.2 Ids que son variantes de otro producto PaceSetter

De los 307 productos que sólo tienen variantes:

- **71 simples** — ningún otro producto de la tienda tiene variantes de ese producto PaceSetter: basta con poner el id del producto en su lista.
- **236 repartidos** — la tienda dividió un producto PaceSetter en varios de Shopify (por color, medida o estado). Apuntarlos todos al mismo id los haría colisionar, y sincronizar cualquiera se llevaría **todas** sus variantes. Hay que decidir: fusionarlos en un producto de Shopify, o que la app admita que un producto cubra sólo *algunas* variantes de un producto PaceSetter.

| producto | id de variante → producto PaceSetter | estado |
|---|---|---|
| [Walnut Finish Plaque with Marble Mist Plate](https://admin.shopify.com/store/trophy-partner/products/15512161484911) | `C021ABWF` → C021A; `C021AGWF` → C021A; `C021ARWF` → C021A; `C021BBWF` → C021B; `C021BGWF` → C021B; `C021BRWF` → C021B | UNSYNCABLE_PARTS · repartido |
| [Walnut Finish Plaque with Black Florentine](https://admin.shopify.com/store/trophy-partner/products/15512161550447) | `C071BSWF` → C071B | UNSYNCABLE_PARTS · repartido |
| [Teal Chisel Carve Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512162173039) | `C701LTE` → C701L | UNSYNCABLE_PARTS · repartido |
| [Teal Chisel Carve Tower Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512162205807) | `C701BLTE` → C701BL | UNSYNCABLE_PARTS · repartido |
| [Taper Edge Award](https://admin.shopify.com/store/trophy-partner/products/15512162238575) | `GM750ABK` → GM750; `GM750ABL` → GM750; `GM750BBK` → GM750; `GM750BBL` → GM750; `GM751ABK` → GM751 | UNSYNCABLE_PARTS · simple |
| [Tahoe Travel Tumbler](https://admin.shopify.com/store/trophy-partner/products/15512162533487) | `CM747BK` → CM747*; `CM747BL` → CM747*; `CM747DB` → CM747*; `CM747LP` → CM747*; `CM747OR` → CM747*; `CM747PK` → CM747*; `CM747PU` → CM747*; `CM747RD` → CM747*; `CM746BK` → CM746*; `CM746BL` → CM746*; `CM746DB` → CM746*; `CM746LP` → CM746*; `CM746OR` → CM746*; `CM746PK` → CM746*; `CM746PU` → CM746*; `CM746RD` → CM746* | UNSYNCABLE_PARTS · repartido |
| [Tahoe Travel Mug](https://admin.shopify.com/store/trophy-partner/products/15512162664559) | `CM747LB` → CM747*; `CM746LB` → CM746* | UNSYNCABLE_PARTS · repartido |
| [Tahoe Stemless Cocktail Tumblers](https://admin.shopify.com/store/trophy-partner/products/15512163025007) | `CM745PK` → CM745*; `CM745RD` → CM745*; `CM745TL` → CM745* | UNSYNCABLE_PARTS · repartido |
| [Tahoe Stemless Cocktail Tumbler](https://admin.shopify.com/store/trophy-partner/products/15512163123311) | `CM745BK` → CM745*; `CM745BL` → CM745*; `CM745DB` → CM745*; `CM745LB` → CM745*; `CM745LP` → CM745*; `CM745PU` → CM745* | UNSYNCABLE_PARTS · repartido |
| [Tahoe Drinkware](https://admin.shopify.com/store/trophy-partner/products/15512163352687) | `CM814BK` → CM814; `CM814BL` → CM814; `CM814CR` → CM814; `CM814DB` → CM814; `CM814GR` → CM814; `CM814GY` → CM814; `CM814LB` → CM814; `CM814LP` → CM814; `CM814MR` → CM814; `CM814OR` → CM814; `CM814PK` → CM814; `CM814PU` → CM814; `CM814RD` → CM814; `CM814TL` → CM814; `CM814WH` → CM814; `CM814YW` → CM814 | UNSYNCABLE_PARTS · simple |
| [Small Red Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512163975279) | `C750ALR` → C750 | UNSYNCABLE_PARTS · repartido |
| [Small Green Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512164106351) | `C750ALGN` → C750 | UNSYNCABLE_PARTS · repartido |
| [Small Gold Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512164204655) | `C750ALGO` → C750 | UNSYNCABLE_PARTS · repartido |
| [Simplicity Plate on Walnut Finish Board](https://admin.shopify.com/store/trophy-partner/products/15512164499567) | `C075BB` → C075; `C075BGO` → C075; `C075BR` → C075 | UNSYNCABLE_PARTS · repartido |
| [Simplicity Plate on Ebony Board](https://admin.shopify.com/store/trophy-partner/products/15512164630639) | `C076BB` → C076; `C076BGO` → C076 | UNSYNCABLE_PARTS · repartido |
| [Rose Leatherette Oval Keychain](https://admin.shopify.com/store/trophy-partner/products/15512165515375) | `CM291RS` → CM291* | UNSYNCABLE_PARTS · repartido |
| [Red Marble Florentine Plate on Walnut Finish Board](https://admin.shopify.com/store/trophy-partner/products/15512165679215) | `CD414ARWF` → CD414*; `CD414BRWF` → CD414* | UNSYNCABLE_PARTS · repartido |
| [Rawhide Leatherette Phone Easel](https://admin.shopify.com/store/trophy-partner/products/15512165777519) | `CM296RW` → CM296 | UNSYNCABLE_PARTS · repartido |
| [Rawhide Leatherette Oval Keychain](https://admin.shopify.com/store/trophy-partner/products/15512165843055) | `CM291RW` → CM291* | UNSYNCABLE_PARTS · repartido |
| [Powder Coated Tumbler Large](https://admin.shopify.com/store/trophy-partner/products/15512166137967) | `CM816BK` → CM816* | UNSYNCABLE_PARTS · repartido |
| [Powder Coated Tumbler Small](https://admin.shopify.com/store/trophy-partner/products/15512166269039) | `CM816BL` → CM816*; `CM816DB` → CM816*; `CM816MR` → CM816*; `CM816PK` → CM816*; `CM816PU` → CM816*; `CM816RD` → CM816*; `CM816TL` → CM816*; `CM816WH` → CM816* | UNSYNCABLE_PARTS · repartido |
| [Polar Camel Medium](https://admin.shopify.com/store/trophy-partner/products/15512166629487) | `CM712BL` → CM712; `CM712DB` → CM712; `CM712GR` → CM712; `CM712GY` → CM712; `CM712LB` → CM712; `CM712LP` → CM712; `CM712MR` → CM712; `CM712OR` → CM712; `CM712PK` → CM712; `CM712PU` → CM712; `CM712RD` → CM712; `CM712TL` → CM712; `CM712WH` → CM712; `CM713BK` → CM713*; `CM731BK` → CM731 | UNSYNCABLE_PARTS · repartido |
| [Polar Camel Small](https://admin.shopify.com/store/trophy-partner/products/15512166695023) | `CM713BL` → CM713*; `CM713DB` → CM713*; `CM713GR` → CM713*; `CM713GY` → CM713*; `CM713LB` → CM713*; `CM713LP` → CM713*; `CM713MR` → CM713*; `CM713OR` → CM713*; `CM713PK` → CM713*; `CM713PU` → CM713*; `CM713RD` → CM713*; `CM713TL` → CM713*; `CM713WH` → CM713*; `CM711BL` → CM711; `CM711CR` → CM711; `CM711DB` → CM711; `CM711GR` → CM711; `CM711GY` → CM711; `CM711LB` → CM711; `CM711LP` → CM711; `CM711MR` → CM711; `CM711OG` → CM711; `CM711OR` → CM711; `CM711PK` → CM711; `CM711PU` → CM711; `CM711RD` → CM711; `CM711TL` → CM711; `CM711WH` → CM711; `CM711YW` → CM711; `CM712BK` → CM712; `CM726BL` → CM726*; `CM726CR` → CM726*; `CM726DB` → CM726*; `CM726GR` → CM726*; `CM726GY` → CM726*; `CM726LB` → CM726*; `CM726LP` → CM726*; `CM726MR` → CM726*; `CM726OR` → CM726*; `CM726PK` → CM726*; `CM726PU` → CM726*; `CM726RD` → CM726*; `CM726TL` → CM726*; `CM726WH` → CM726*; `CM726YW` → CM726*; `CM731BL` → CM731; `CM731CR` → CM731; `CM731DB` → CM731; `CM731GR` → CM731; `CM731GY` → CM731 | UNSYNCABLE_PARTS · repartido |
| [Polar Camel stainless steel beverage holder. Large](https://admin.shopify.com/store/trophy-partner/products/15512166826095) | `CM727BL` → CM727*; `CM727CR` → CM727*; `CM727DB` → CM727*; `CM727GR` → CM727*; `CM727GY` → CM727*; `CM727LB` → CM727*; `CM727LP` → CM727*; `CM727MR` → CM727*; `CM727OR` → CM727*; `CM727PK` → CM727*; `CM727PU` → CM727*; `CM727RD` → CM727*; `CM727TL` → CM727*; `CM727WH` → CM727*; `CM727YW` → CM727*; `CM737BL` → CM737*; `CM737CR` → CM737*; `CM737DB` → CM737*; `CM737GR` → CM737*; `CM737GY` → CM737*; `CM737LB` → CM737*; `CM737LP` → CM737*; `CM737MR` → CM737*; `CM737OR` → CM737*; `CM737PK` → CM737*; `CM737PU` → CM737*; `CM737RD` → CM737*; `CM737TL` → CM737*; `CM737WH` → CM737*; `CM737YW` → CM737* | UNSYNCABLE_PARTS · repartido |
| [Polar Camel stainless steel beverage holder. Small](https://admin.shopify.com/store/trophy-partner/products/15512166957167) | `CM727BK` → CM727*; `CM737BK` → CM737* | UNSYNCABLE_PARTS · repartido |
| [Plaque w/ Green Marble Mist](https://admin.shopify.com/store/trophy-partner/products/15512167088239) | `C023AGR` → C023 | UNSYNCABLE_PARTS · repartido |
| [Pink Leatherette Oval Keychain](https://admin.shopify.com/store/trophy-partner/products/15512167350383) | `CM291PK` → CM291* | UNSYNCABLE_PARTS · repartido |
| [Medium Red Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512168169583) | `C750BLR` → C750 | UNSYNCABLE_PARTS · repartido |
| [Medium Green Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512168202351) | `C750BLGN` → C750 | UNSYNCABLE_PARTS · repartido |
| [Medium Gold Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512168267887) | `C750BLGO` → C750 | UNSYNCABLE_PARTS · repartido |
| [Medium Blue Chisel Carve Tower Award](https://admin.shopify.com/store/trophy-partner/products/15512168300655) | `C610LBL` → C610L | UNSYNCABLE_PARTS · repartido |
| [Light Brown Leatherette Phone Easel](https://admin.shopify.com/store/trophy-partner/products/15512168464495) | `CM296LB` → CM296 | UNSYNCABLE_PARTS · repartido |
| [Light Brown Leatherette Oval Keychain](https://admin.shopify.com/store/trophy-partner/products/15512168497263) | `CM291LB` → CM291* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Square Coasters Set](https://admin.shopify.com/store/trophy-partner/products/15512168857711) | `CM283DB` → CM283*; `CM283GR` → CM283*; `CM283LB` → CM283*; `CM283PK` → CM283*; `CM283RW` → CM283*; `CM283TL` → CM283* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Square Coaster](https://admin.shopify.com/store/trophy-partner/products/15512168956015) | `CM241BS` → CM241*; `CM241DB` → CM241*; `CM241GR` → CM241*; `CM241LB` → CM241*; `CM241PK` → CM241*; `CM241RS` → CM241*; `CM241RW` → CM241*; `CM241TL` → CM241* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Slim Large](https://admin.shopify.com/store/trophy-partner/products/15512169021551) | `CM292BS` → CM292 | UNSYNCABLE_PARTS · repartido |
| [Leatherette Slim Small](https://admin.shopify.com/store/trophy-partner/products/15512169087087) | `CM292DB` → CM292; `CM292GR` → CM292; `CM292LB` → CM292; `CM292RW` → CM292 | UNSYNCABLE_PARTS · repartido |
| [Leatherette Set of 6 Round Coasters](https://admin.shopify.com/store/trophy-partner/products/15512169119855) | `CM282DB` → CM282*; `CM282GR` → CM282*; `CM282LB` → CM282*; `CM282PK` → CM282*; `CM282RW` → CM282*; `CM282TL` → CM282* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Round Coaster](https://admin.shopify.com/store/trophy-partner/products/15512169152623) | `CM240BS` → CM240*; `CM240DB` → CM240*; `CM240GR` → CM240*; `CM240LB` → CM240*; `CM240PK` → CM240*; `CM240RS` → CM240*; `CM240RW` → CM240*; `CM240TL` → CM240* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Portfolio Small](https://admin.shopify.com/store/trophy-partner/products/15512169218159) | `CM237ABS` → CM237A*; `CM237ACK` → CM237A*; `CM237ATL` → CM237A*; `CM237BBS` → CM237B*; `CM237BCK` → CM237B*; `CM237BTL` → CM237B* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Portfolio with Notepad (sml)](https://admin.shopify.com/store/trophy-partner/products/15512169381999) | `CM237ABL` → CM237A*; `CM237ADB` → CM237A*; `CM237AGR` → CM237A*; `CM237ALB` → CM237A*; `CM237APK` → CM237A*; `CM237ARS` → CM237A*; `CM237ARW` → CM237A* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Portfolio with Notepad (lrg)](https://admin.shopify.com/store/trophy-partner/products/15512169513071) | `CM237BBL` → CM237B*; `CM237BDB` → CM237B*; `CM237BGR` → CM237B*; `CM237BLB` → CM237B*; `CM237BRS` → CM237B*; `CM237BRW` → CM237B* | UNSYNCABLE_PARTS · repartido |
| [Leatherette on Steel Shot Glass](https://admin.shopify.com/store/trophy-partner/products/15512169807983) | `CM373RV` → CM373BB, CM373BS, CM373BV, CM373DB, CM373GR, CM373LB, CM373PK, CM373RW, CM373TL, CM373WM | OK · simple |
| [Leatherette Notepad and Pen Small](https://admin.shopify.com/store/trophy-partner/products/15512169840751) | `CM244BB` → CM244* | UNSYNCABLE_PARTS · repartido |
| [Large Leatherette Notepad and Pen](https://admin.shopify.com/store/trophy-partner/products/15512169873519) | `CM244BS` → CM244*; `CM244BV` → CM244*; `CM244DB` → CM244*; `CM244GR` → CM244*; `CM244LB` → CM244*; `CM244PK` → CM244*; `CM244RS` → CM244*; `CM244RW` → CM244*; `CM244TL` → CM244*; `CM244WM` → CM244* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Money Clip Large](https://admin.shopify.com/store/trophy-partner/products/15512170233967) | `CM455DB` → CM455; `CM455GR` → CM455; `CM455LB` → CM455; `CM455RW` → CM455 | UNSYNCABLE_PARTS · repartido |
| [Leatherette Money Clip Small](https://admin.shopify.com/store/trophy-partner/products/15512170266735) | `CM455BS` → CM455 | UNSYNCABLE_PARTS · repartido |
| [Leatherette Hard Business Card Case](https://admin.shopify.com/store/trophy-partner/products/15512170430575) | `CM243BS` → CM243*; `CM243DB` → CM243*; `CM243GR` → CM243*; `CM243LB` → CM243*; `CM243PK` → CM243*; `CM243RS` → CM243*; `CM243RW` → CM243*; `CM243TL` → CM243* | UNSYNCABLE_PARTS · repartido |
| [Leatherette Cigar Case with Cutter](https://admin.shopify.com/store/trophy-partner/products/15512171020399) | `CM372BS` → CM372; `CM372BV` → CM372; `CM372DB` → CM372; `CM372GR` → CM372; `CM372LB` → CM372; `CM372RV` → CM372; `CM372RW` → CM372 | UNSYNCABLE_PARTS · repartido |
| [Leatherette Bottle Opener](https://admin.shopify.com/store/trophy-partner/products/15512171184239) | `CM332BS` → CM332*; `CM332DB` → CM332*; `CM332GR` → CM332*; `CM332LB` → CM332*; `CM332PK` → CM332*; `CM332RD` → CM332*; `CM332RS` → CM332*; `CM332RW` → CM332*; `CM333BS` → CM333; `CM333DB` → CM333; `CM333GR` → CM333; `CM333LB` → CM333 | UNSYNCABLE_PARTS · repartido |
| [Layered Acrylic Plaque w/Green and Circle](https://admin.shopify.com/store/trophy-partner/products/15512171348079) | `CD961GR` → CD961 | UNSYNCABLE_PARTS · repartido |
| [Layered Acrylic Plaque w/Blue Back and Circle](https://admin.shopify.com/store/trophy-partner/products/15512171446383) | `CD961BL` → CD961 | UNSYNCABLE_PARTS · repartido |
| [Large Red Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512171610223) | `C750CLR` → C750 | UNSYNCABLE_PARTS · repartido |
| [Large Green Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512171642991) | `C750CLGN` → C750 | UNSYNCABLE_PARTS · repartido |
| [Large Gold Halley Plaque](https://admin.shopify.com/store/trophy-partner/products/15512171708527) | `C750CLGO` → C750 | UNSYNCABLE_PARTS · repartido |
| [Large Digi Large](https://admin.shopify.com/store/trophy-partner/products/15512171806831) | `CD547BEB` → CD547B | UNSYNCABLE_PARTS · repartido |
| [Grey Leatherette Phone Easel](https://admin.shopify.com/store/trophy-partner/products/15512171970671) | `CM296GR` → CM296 | UNSYNCABLE_PARTS · repartido |
| [Grey Leatherette Oval Keychain](https://admin.shopify.com/store/trophy-partner/products/15512172036207) | `CM291GR` → CM291* | UNSYNCABLE_PARTS · repartido |
| [Green Vibrant Gemstone Award](https://admin.shopify.com/store/trophy-partner/products/15512172134511) | `GI512AGR` → GI512; `GI512BGR` → GI512 | UNSYNCABLE_PARTS · repartido |
| [Green Optic Crystal Gemstone Award](https://admin.shopify.com/store/trophy-partner/products/15512172232815) | `GI513AGR` → GI513A; `GI513BGR` → GI513B; `GI513CGR` → GI513C | UNSYNCABLE_PARTS · repartido |

… 254 más en el CSV.

### 3.3 Un producto de Shopify, varias líneas de PaceSetter

No es un error por sí mismo — el catálogo antiguo agrupaba medidas y familias a propósito (p-8887). Pero donde los nombres de la derecha son artículos claramente distintos, deberían ser productos separados.

| producto | líneas | nombres en PaceSetter | ≈ variantes tras la sync |
|---|---|---|---|
| [2-Layer Multi-Dimensional Digi-Color Award](https://admin.shopify.com/store/trophy-partner/products/15512270962799) | DCC0501A DCC0501B | 2-Layer Multi-Dimensional Digi-Color Award | 2 |
| [2-Tone Bamboo Cutting Board](https://admin.shopify.com/store/trophy-partner/products/15512270930031) | CM419A CM419B | 2-Tone Bamboo Cutting Board - Large; 2-Tone Bamboo Cutting Board - Small | 2 |
| [Aquus Lucite Peak Award](https://admin.shopify.com/store/trophy-partner/products/15512268931183) | CDUS02A CDUS02B | Aquus Lucite Peak Award - Medium; Aquus Lucite Peak Award - Small | 3 |
| [Aquus Series Lucite Tablet Award](https://admin.shopify.com/store/trophy-partner/products/15512268800111) | CDUS01A CDUS01B | Aquus Lucite Tablet Award - Large; Aquus Lucite Tablet Award - Medium | 2 |
| [Ashbourne Series Diamond On Base](https://admin.shopify.com/store/trophy-partner/products/15512267522159) | GM410A GM410B GM410C | Diamond on Base - Large; Diamond on Base - Medium; Diamond on Base - Small | 3 |
| [Avignon Crescents Beveled Jade Glass Plaque Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512267391087) | GM456A GM456B | Beveled Jade Glass Crescent Plaque | 2 |
| [Beveled Crystal Circle Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512208932975) | GM757B GM757C | Beveled Crystal Circle, Lrg; Beveled Crystal Circle, Med | 2 |
| [Beveled Jade Glass Award Plaque](https://admin.shopify.com/store/trophy-partner/products/15512267423855) | GM456C GM456D | Beveled Jade Glass Crescent Plaque | 2 |
| [Beveled Jade Glass Crescent Plaque](https://admin.shopify.com/store/trophy-partner/products/15512266539119) | GM665A GM665B GM665C GM665D | Beveled Jade Glass Crescent Plaque | 4 |
| [Beveled Jade Glass Crescent Plaque Small](https://admin.shopify.com/store/trophy-partner/products/15512208474223) | GM823C GM823D GM823E | Beveled Jade Glass Crescent Plaque | 3 |
| [Black Diamond Carve Lucite Award](https://admin.shopify.com/store/trophy-partner/products/15512207687791) | CD1103A CD1103B | Black Diamond Carve, Med; Black Diamond Carve, Small | 2 |
| [Blue  Accent Clear Center Crystal](https://admin.shopify.com/store/trophy-partner/products/15512177442927) | GM809A* GM809B* GM809C* | Blue  Accent Clear Center Crystal | 6 |
| [Blue Award with Clear Lucite Base](https://admin.shopify.com/store/trophy-partner/products/15512265162863) | C0613 C0614 C0610 C0611 C0612 | Enterprise Large Blue Dynasty Award; Large Blue Dynasty Award; Large Blue Dynasty Award with Clear Lucite Base; Small Blue Dynasty Award; Small Blue Dynasty Award with Clear Lucite Base | 5 |
| [Bold Clear Crystal Facet Oval](https://admin.shopify.com/store/trophy-partner/products/15512176361583) | GI647A GI647B GI647C | Bold Clear Crystal Facet Oval; Bold Clear Crystal Facet Oval, Med; Bold Clear Crystal Facet Oval, Small | 3 |
| [Bold Clear Crystal Wedge Award](https://admin.shopify.com/store/trophy-partner/products/15512263196783) | GI635A GI635B | Bold Clear Crystal Wedge - Large; Bold Clear Crystal Wedge - Medium | 2 |
| [Canvas Portfolio with Leatherette Accent](https://admin.shopify.com/store/trophy-partner/products/15512176197743) | CM720A CM720B | Canvase Portfolio with Leatherette Accent | 11 |
| [Carved Flame with Blue Accent Award](https://admin.shopify.com/store/trophy-partner/products/15512206508143) | GM822A GM822B | Carved Flame with Blue Accent | 2 |
| [Clear Beveled Arrowhead Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512260935791) | GM716A GM716B GM716C | Clear Beveled Glass Arrowhead on Base - Large; Clear Beveled Glass Arrowhead on Base - Medium; Clear Glass Arrowhead on Base,  | 3 |
| [Clear Crystal Star Award on Base](https://admin.shopify.com/store/trophy-partner/products/15512260542575) | GM711A GM711B GM711C | Clear Crystal Star on Base - Large; Clear Crystal Star on Base - Medium; Clear Crystal Star on Base - Small | 3 |
| [Clear Crystal Wave Small](https://admin.shopify.com/store/trophy-partner/products/15512205328495) | GM817B GM817C | Clear Crystal Wave | 2 |
| [Clear Monolith with Blue Insert](https://admin.shopify.com/store/trophy-partner/products/15512204869743) | GM748A GM748B | Clear Monolith with Blue Insert, Large; Clear Monolith with Blue Insert, Small | 2 |
| [Clear Star Award with Blue Triangle Base](https://admin.shopify.com/store/trophy-partner/products/15512204836975) | GM746A GM746B | Clear Star with Blue Triangle Base, Large; Clear Star with Blue Triangle Base, Small | 2 |
| [Clear Star Power Sculptured Lucite Award](https://admin.shopify.com/store/trophy-partner/products/15512259461231) | CA21AX* CA21BX* | Sculptured Lucite Star - Medium; Sculptured Lucite Star - Small | 6 |
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
| [Custom Lasercut Lucite Awards on Black Base](https://admin.shopify.com/store/trophy-partner/products/15512271781999) | CD561B CD561C CD561D CD561E | Laser-Cut Lucite Contour 1/2" Thick Up To 33 Sq In; Laser-Cut Lucite Contour 1/2" Thick Up To 47 Sq In; Laser-Cut Lucite Contour 1/2" Thick Up To 62 Sq In; Laser-Cut Lucite Contour 1/2" Thick Up To 79 | 4 |
| [Custom Layered Lasercut Awards](https://admin.shopify.com/store/trophy-partner/products/15512228561007) | CD571B CD571C | Layered Laser-Cut Lucite Contour 3/8" Thick Up To 33 Sq In; Layered Laser-Cut Lucite Contour 3/8" Thick Up To 47 Sq In | 2 |
| [Deep Beveled Back On Base](https://admin.shopify.com/store/trophy-partner/products/15512256708719) | GM411A GM411B GM411C | Deep Beveled Back on Base - Large; Deep Beveled Back on Base - Medium; Deep Beveled Back on Base - Small | 3 |
| [Diamond Bevel Glass and Marble Pillar Base Award](https://admin.shopify.com/store/trophy-partner/products/15512256610415) | GM726A GM726B | Diamond Bevel Glass and Marble Pillar Base - Large; Diamond Bevel Glass and Marble Pillar Base - Small | 2 |
| [Diamond Columns](https://admin.shopify.com/store/trophy-partner/products/15512202477679) | GM818A GM818B GM818C | Diamond Columns | 3 |
| [Diamond Glass Award On Chrome Base](https://admin.shopify.com/store/trophy-partner/products/15512257134703) | GI27A GI27B | Starphire Glass with Brushed Silver Base- Large; Starphire Glass with Brushed Silver Base- Medium | 2 |
| [Ebony Finish 12-Plt Layered Blue and Silver Border Plaque with Easy Perpetual Plate Release Program](https://admin.shopify.com/store/trophy-partner/products/15512168333423) | EP9 EP9PK | Ebony Finish 12-Plt Layered Blue and Silver Border Plaque  with Easy Perpetual Plate Release Program; Ebony Finish 12-Plt Layered Blue and Silver Border Plaque  with Easy Perpetual Plate Release Progr | 2 |
| [Ebony Plaque with Raised Starphire Glass](https://admin.shopify.com/store/trophy-partner/products/15512255987823) | G0655A G0655B | Ebony Piano Finish Board with Raised Starphire Glass | 2 |
| [Etched Premium Standing Jade Tablet Large](https://admin.shopify.com/store/trophy-partner/products/15512255660143) | GM446A GM446B | Premium Standing Jade Glass Tablet - Medium; Premium Standing Jade Glass Tablet - Small | 2 |
| [Faceted Circle Wedge on Slant Side Base Large](https://admin.shopify.com/store/trophy-partner/products/15512201592943) | GM815A GM815B | Faceted Circle Wedge on Slant Side Base | 2 |
| [Faceted Edge Circle Award on Slant Base](https://admin.shopify.com/store/trophy-partner/products/15512201265263) | GM814A GM814B GM814C | Faceted Edge Circle on Slant Base | 3 |
| [Florence Series Faceted Crystal Circle Award](https://admin.shopify.com/store/trophy-partner/products/15512255365231) | GM615A GM615B GM615C | Faceted Crystal Circle - Large; Faceted Crystal Circle - Medium; Faceted Crystal Circle - Small | 3 |
| [Freestanding Lucite Block](https://admin.shopify.com/store/trophy-partner/products/15512254939247) | C0813A C0813B | Freestanding Lucite Block | 2 |
| [Glass Droplets Indigo Stream Art Glass Award](https://admin.shopify.com/store/trophy-partner/products/15512234426479) | GM435B GM435C | Indigo Stream Art Glass - Large (Includes Silver Color-Fill on Base Only); Indigo Stream Art Glass - Medium (Includes Silver Color-Fill on Base Only) | 2 |
| [Glass Shield w/ Diamond On Base](https://admin.shopify.com/store/trophy-partner/products/15512234295407) | GM617A GM617B GM617C | Reflective Glass Shield with Diamond  on Black Glass Base - Large; Reflective Glass Shield with Diamond  on Black Glass Base - Medium; Reflective Glass Shield with Diamond  on Black Glass Base - Small | 3 |
| [Gold Star Power Sculptured Lucite Award](https://admin.shopify.com/store/trophy-partner/products/15512232558703) | CA21AX* CA21BX* | Sculptured Lucite Star - Medium; Sculptured Lucite Star - Small | 6 |
| [Grooved Brilliance Plaque](https://admin.shopify.com/store/trophy-partner/products/15512231837807) | CD959G CD959* | Grooved Brilliance Plaque | 2 |
| [Interlocking Perpetual Award Bars](https://admin.shopify.com/store/trophy-partner/products/15512161124463) | CD908RL CD908RR | Rectangle Interlocking Bar, Left; Rectangle Interlocking Bar, Right | 4 |
| [Jade Faceted Circle Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512231575663) | GM663A GM663B GM663C | Pearl Edge Circle Clear - Large; Pearl Edge Circle Clear - Medium; Pearl Edge Circle Clear - Small | 3 |
| [Jade Faceted Flame Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512231510127) | GM662A GM662B GM662C | Pearl Edge Flame Clear - Large; Pearl Edge Flame Clear - Medium; Pearl Edge Flame Clear - Small | 3 |
| [Jade Glass Circle On Base](https://admin.shopify.com/store/trophy-partner/products/15512231444591) | GI496A GI496B | Jade Glass Circle on Base - Large; Jade Glass Circle on Base - Medium | 2 |
| [Jade Glass Slant Peak On Base Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512230887535) | GI527A GI527B GI527C | Jade Glass Slant Peak on Base - Large; Jade Glass Slant Peak on Base - Medium; Jade Glass Slant Peak on Base - Small | 3 |
| [Jade Glass Star Crescent Award Trophy](https://admin.shopify.com/store/trophy-partner/products/15512230789231) | GM668A GM668B | Jade Glass Star Crescent | 2 |
| [Large Rectangular Custom Digi Color Lucite Award](https://admin.shopify.com/store/trophy-partner/products/15512256905327) | DCCD424** DCCD424B | Medium Digi-Color Lucite Award | 5 |
| [Laser Carved Alder Wood Plaque](https://admin.shopify.com/store/trophy-partner/products/15512230101103) | CD83A CD83B | Genuine Alder Wood Plaque | 2 |
| [Laser Etched Premium Flame On Base](https://admin.shopify.com/store/trophy-partner/products/15512229871727) | GM442A GM442B GM442C | Premium Jade Glass Flame on Base - Large; Premium Jade Glass Flame on Base - Medium; Premium Jade Glass Flame on Base - Small | 3 |

… 37 más en el CSV.

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

… 179 más en el CSV.

### 3.5 Avisos en productos que sí se sincronizan

| aviso | productos |
|---|---|
| la unión añade ids a `ps_product_ids` | 125 |
| la unión alcanza ids de otro producto de la tienda | 125 |
| ids muertos en `ps_product_ids` | 22 |
| lista ids de variante de otro producto | 7 |
| el id canónico no sirve (arranca otro id de la lista) | 6 |

### 3.6 Una sync se tragaría a productos hermanos

El Inventory de PaceSetter responde por familia entera, y `ForeignProductSync.union` adopta cada miembro de la familia que es producto propio. Está bien cuando el producto de la tienda ES la familia (portafolio CM297); está mal cuando la tienda vende cada color o estado como producto propio: sincronizar uno lo convierte en la familia entera y añade los ids de los hermanos a su `ps_product_ids`. **125 productos** harían esto; no deben sincronizarse hasta que la app no adopte ids que ya reclama otro producto.

| línea PaceSetter | productos | ≈ variantes cada uno tras la sync | p. ej. |
|---|---|---|---|
| CD900* | 52 | 53 | Frosted Lucite Star Cutout on Risers Award; Wyoming State Silhouette Awards; West Virginia State Silhouette Awards |
| CD953* | 40 | 51 | Frosted Acrylic WY State Cutout on Black Plaque; Frosted Acrylic WV State Cutout on Black Plaque; Frosted Acrylic WI State Cutout on Black Plaque |
| CM286* | 3 | 12 | Leatherette Flask; Leatherette Flask; Cork Flask |
| CM289* | 3 | 13 | Leatherette Beverage Holder Large; Leatherette Beverage Holder; Cork Beverage Holder |
| C1407L* | 3 | 3 | Blue Diamond Carve Lucite Octagon Plaque; Red Diamond Carve Lucite Octagon Plaque; Green Diamond Carved Octagon Plaque |
| GI586* | 2 | 2 | Two-Tone Clear and Blue Crescent Curved Glass (Includes Silver Color; Clear and Black Crescent Curved Glass Award Trophy |
| CM288* | 2 | 4 | Leatherette Wine Bag Large; Leatherette Wine Bag Small |
| CM415* | 2 | 12 | Leatherette Journal; Leatherette Journal |
| CM721* | 2 | 3 | Journal with Phone Pouch Large; Journal with Phone Pouch Small |
| GM809A* GM809B* GM809C* | 2 | 6 | Blue  Accent Clear Center Crystal; Red Accent Clear Center Crystal |
| CD121* | 2 | 2 | Slide-in Certificate Rosewood Plaque in Mailer Box; Slide-in Certificate Rosewood Plaque in Gift Box |
| C6801* C6802* | 2 | 4 | Silver Lucite Riser Plaque; Lucite Riser Plaque with Choice Of Plate |
| CA21AX* CA21BX* | 2 | 6 | Gold Star Power Sculptured Lucite Award; Clear Star Power Sculptured Lucite Award |
| CA21CX* | 2 | 3 | Gold Star Power Sculptured Lucite Small; Clear Star Power Sculptured Lucite Large |
| CM722A | 1 | 7 | Leatherette Frame  Holds Large |
| CM722A CM722B | 1 | 14 | Leatherette Frame  Holds Small |
| DCCD424** DCCD425** | 1 | 4 | Rectangular Digi Color Lucite Ribbon Award |
| DCC3001C* | 1 | 2 | Choice of Digi Medium |
| DCC3001C* DCC3001D* DCC3001E* | 1 | 6 | Walnut Finish Plaque w/ Choice Of Plate & Board |
| DCCD424** DCCD424B | 1 | 5 | Large Rectangular Custom Digi Color Lucite Award |

### 4. Productos PaceSetter que ningún producto de la tienda reclama

Ninguno se crea: con `sync.create-products.enabled=false` una importación de un id que no está en la tienda se rechaza con un 409.

| veredicto | ids | significado |
|---|---|---|
| ABSORBED | 5 | la sync de un producto de la tienda lo añadirá como variante |
| DUPLICATE_RISK | 73 | sus variantes ya están en un producto de la tienda con otros ids — es el id al que deberían apuntar (3.2) |
| NEW | 292 | no está en la tienda de ninguna forma — se ignora |
| NOT_IMPORTABLE | 147 | sin precio o sin ficha |

| id PaceSetter | nombre | ya está en |
|---|---|---|
| `CM240*` | Leatherette Round Coaster | p-8817-leatherette-round-coaster, p-8098-leatherette-round-coaster, p-5623-cork-round-coaster, p-8817-leatherette-round-coaster |
| `CM241*` | Leatherette Square Coaster | p-8821-leatherette-square-coaster, p-5625-cork-square-coaster, p-8821-leatherette-square-coaster, p-5625-cork-square-coaster |
| `CM291*` | Leatherette Oval Keychain | p-8770-grey-leatherette-oval-keychain, p-6014-cork-leatherette-oval-keychain, p-8091-leatherette-oval-keychain, p-8859-rose-leatherette-oval-keychain |
| `CM243*` | Leatherette Hard Business Card Case | p-8796-leatherette-hard-business-card-case, p-8084-leatherette-hard-business-card-case, p-8796-leatherette-hard-business-card-case, p-8084-leatherette-hard-business-card-case |
| `CM244*` | Leatherette Notepad and Pen | p-8805-large-leatherette-notepad-and-pen, p-8806-leatherette-notepad-and-pen-small, p-8806-leatherette-notepad-and-pen-small, p-8805-large-leatherette-notepad-and-pen |
| `CM237A*` | Leatherette Portfolio | p-8814-leatherette-portfolio-with-notepad-sml, p-8096-leatherette-portfolio, p-8815-leatherette-portfolio-small, p-8815-leatherette-portfolio-small |
| `CM237B*` | Leatherette Portfolio | p-8813-leatherette-portfolio-with-notepad-lrg, p-8815-leatherette-portfolio-small, p-8096-leatherette-portfolio, p-8815-leatherette-portfolio-small |
| `CM710*` | Polar Camel 30 oz. Ringneck Tumbler | p-6168-polar-camel-ringneck-tumblers-personalized |
| `CM713*` | Polar Camel 16 oz. Stemless Tumbler | p-8848-polar-camel-small, p-8850-polar-camel-medium, p-8850-polar-camel-medium, p-8848-polar-camel-small |
| `CM282*` | Cork Round 6-Coaster Set | p-8818-leatherette-set-of-6-round-coasters, p-5624-cork-round, p-8818-leatherette-set-of-6-round-coasters, p-5624-cork-round |
| `CM283*` | Leatherette Square 6-Coaster Set | p-8822-leatherette-square-coasters-set, p-5626-cork-square-coasters-set, p-8822-leatherette-square-coasters-set, p-5626-cork-square-coasters-set |
| `DCC3001BEB` | Choice of Digi-Color Plate on Economy Board | p-8730-choice-of-digi-large, p-8730-choice-of-digi-large |
| `CM332*` | Leatherette Bottle Opener | p-8784-leatherette-bottle-opener, p-5618-cork-bottle-opener, p-8784-leatherette-bottle-opener, p-5618-cork-bottle-opener |
| `CM726*` | Polar Camel 15 oz. Mug, Black                                              | p-8848-polar-camel-small, p-8848-polar-camel-small |
| `CM723*` | Golf Bag Tag with Tees | p-8761-golf-bag-tag-with-tees, p-8762-leatherette-golf-bag-tag-with-tees, p-8762-leatherette-golf-bag-tag-with-tees, p-8761-golf-bag-tag-with-tees |
| `CM745*` | Tahoe Insulated Stemless Tumbler 16 oz.  | p-8884-tahoe-stemless-cocktail-tumbler, p-8885-tahoe-stemless-cocktail-tumblers, p-8885-tahoe-stemless-cocktail-tumblers, p-8884-tahoe-stemless-cocktail-tumbler |
| `CM746*` | Tahoe Insulated Stemless Tumbler 30 oz.  | p-8887-tahoe-travel-tumbler, p-8886-tahoe-travel-mug, p-8887-tahoe-travel-tumbler, p-8886-tahoe-travel-mug |
| `CM747*` | Tahoe Insulated Tumbler Medium 20oz | p-8887-tahoe-travel-tumbler, p-8886-tahoe-travel-mug, p-8887-tahoe-travel-tumbler, p-8886-tahoe-travel-mug |
| `CD1101A` | Blue Acrylic Color Drops, Small | p-8061-green-acrylic-color-drops-award, p-7888-blue-acrylic-color-drops-award, p-8153-red-acrylic-color-drops-award, p-8153-red-acrylic-color-drops-award |
| `CD1101B` | Blue Acrylic Color Drops, Med | p-8061-green-acrylic-color-drops-award, p-8153-red-acrylic-color-drops-award, p-7888-blue-acrylic-color-drops-award, p-8153-red-acrylic-color-drops-award |
| `CD1101C` | Blue Acrylic Color Drops, Large | p-7888-blue-acrylic-color-drops-award, p-8061-green-acrylic-color-drops-award, p-8061-green-acrylic-color-drops-award, p-7888-blue-acrylic-color-drops-award |
| `GM805` | Blue Teardrop Art Glass | p-8067-green-teardrop-art-glass, p-8158-red-teardrop-art-glass, p-7898-blue-teardrop-art-glass, p-8158-red-teardrop-art-glass |
| `CM727*` | Polar Camel Powder Coated Insulated Can Holder, 12/16 oz. can  | p-8847-polar-camel-stainless-steel-beverage-holder-large, p-8846-polar-camel-stainless-steel-beverage-holder-small, p-8847-polar-camel-stainless-steel-beverage-holder-large, p-8846-polar-camel-stainless-steel-beverage-holder-small |
| `CM731` | Polar Camel Powder Coated Insulated Travel Water Bottle Includes Straw | p-8848-polar-camel-small, p-8850-polar-camel-medium, p-8850-polar-camel-medium, p-8848-polar-camel-small |
| `CM816*` | Powder Coated Tumbler | p-8854-powder-coated-tumbler-large, p-8853-powder-coated-tumbler-small, p-8854-powder-coated-tumbler-large, p-8853-powder-coated-tumbler-small |
| `CD904AY*` | Anniversary Freestanding Single Digit Black 1 Year | p-7627-anniversary-freestanding-3-year-award, p-7628-anniversary-freestanding-1-year-award, p-5377-anniversary-freestanding-5-year-award, p-7628-anniversary-freestanding-1-year-award |
| `C021A` | Ebony Finish Plaque with Marble Mist | p-5713-ebony-finish-plaque-with-marble-mist-large, p-8899-walnut-finish-plaque-with-marble-mist-plate, p-8899-walnut-finish-plaque-with-marble-mist-plate, p-5713-ebony-finish-plaque-with-marble-mist-large |
| `C021B` | Ebony Finish Plaque with Marble Mist | p-8899-walnut-finish-plaque-with-marble-mist-plate, p-5712-ebony-finish-plaque-with-marble-mist-small, p-8899-walnut-finish-plaque-with-marble-mist-plate, p-5712-ebony-finish-plaque-with-marble-mist-small |
| `C0642` | Star Cast Self-Standing Plaque | p-5899-gold-star-self-standing-plaque, p-6249-silver-star-self-standing-plaque, p-6249-silver-star-self-standing-plaque, p-5899-gold-star-self-standing-plaque |
| `C071A` | Florentine Gold Edge Plate on Ebony Board | p-5737-florentine-gold-edge-plate-on-ebony-board, p-8219-walnut-finish-plaque-with-black-florentine, p-6329-walnut-finish-plaque-with-black-florentine-plate, p-5734-florentine-edge-plate-on-ebony-board-medium |
| `C071B` | Florentine Gold Edge Plate on Ebony Board | p-8898-walnut-finish-plaque-with-black-florentine, p-5736-florentine-gold-edge-plate-on-ebony-board-large, p-6329-walnut-finish-plaque-with-black-florentine-plate, p-8898-walnut-finish-plaque-with-black-florentine |
| `C3501A` | Certificate/Overlay Plaque for 7" x 5" Insert | p-6325-walnut-finish-certificateoverlay-plaque-in-mailer-box, p-6324-walnut-finish-certificateoverlay-plaque-in-gift-box, p-5708-ebony-finish-certificateoverlay-plaque, p-5869-genuine-walnut-certificate-plaque |
| `C3501B` | Certificate/Overlay Plaque for 8" x 6" Insert | p-5708-ebony-finish-certificateoverlay-plaque, p-5708-ebony-finish-certificateoverlay-plaque |
| `C3501C` | Certificate/Overlay Plaque for 8½" x 11" Insert | p-5708-ebony-finish-certificateoverlay-plaque, p-6324-walnut-finish-certificateoverlay-plaque-in-gift-box, p-6325-walnut-finish-certificateoverlay-plaque-in-mailer-box, p-6325-walnut-finish-certificateoverlay-plaque-in-mailer-box |
| `C4801AA` | Slide-in Certificate Plaque - Walnut Finish for 7" x 5" Insert | p-6270-slide-in-certificate-walnut-plaque-in-gift-box, p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6270-slide-in-certificate-walnut-plaque-in-gift-box |
| `C4801A` | Slide-in Certificate Plaque - Walnut Finish for 8" x 6" Insert | p-6270-slide-in-certificate-walnut-plaque-in-gift-box, p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6270-slide-in-certificate-walnut-plaque-in-gift-box |
| `C4801B` | Slide-in Certificate Plaque - Walnut Finish for 10" x 8" Insert | p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6270-slide-in-certificate-walnut-plaque-in-gift-box, p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6270-slide-in-certificate-walnut-plaque-in-gift-box |
| `C4801C` | Slide-in Certificate Plaque - Walnut Finish for 11" x 8½" Insert | p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6270-slide-in-certificate-walnut-plaque-in-gift-box, p-6271-slide-in-certificate-walnut-plaque-in-mailer-box, p-6270-slide-in-certificate-walnut-plaque-in-gift-box |
| `C4802` | Certificate Frame with Metallized Accent | p-6321-walnut-certificate-frame-with-gold-metallized-accent, p-8709-black-certificate-frame-with-silver-metallized-accent, p-8709-black-certificate-frame-with-silver-metallized-accent, p-6321-walnut-certificate-frame-with-gold-metallized-accent |
| `C609LB` | Small Chisel Carve Tower on Base | p-5479-blue-chisel-carved-tower-award-on-base, p-8760-gold-chisel-carved-tower-on-base-large, p-8760-gold-chisel-carved-tower-on-base-large, p-5479-blue-chisel-carved-tower-award-on-base |

… 33 más en el CSV.

### 5. Duplicados dentro de la tienda

| comprobación | cantidad |
|---|---|
| `migration.legacy_sku` en más de un producto | 0 |
| mismo título en más de un producto PromoStandards | 12 |
| productos sin tag con SKU `PS…` y sin ids (invisibles para la app) | 5 |

| título | productos |
|---|---|
| crystal tapered monolith | 2 |
| freestanding lucite block | 2 |
| gentle waves clear chisel tower award | 2 |
| leatherette cigar case with cutter | 2 |
| leatherette flask | 2 |
| leatherette hard business card case | 2 |
| leatherette journal | 2 |
| leatherette money clip large | 2 |
| leatherette phone wallet with ring | 2 |
| leatherette round coaster | 2 |
| vivid spiral acrylic on marble | 2 |
| walnut finish plaque with black florentine | 2 |

## Ficheros

- `store_products.csv` — una fila por producto de la tienda: veredicto, id que arranca, tipo de cada id, líneas y nombres en PaceSetter, variantes esperadas, lo que añade la unión.
- `supplier_ids.csv` — una fila por id reclamado: tipo, línea, de qué producto es variante, quién lo reclama.
- `shared_variants.csv` — cada variante PaceSetter que acabaría en más de un producto tras la sync.
- `unclaimed_sellable.csv` — productos PaceSetter que ningún producto de la tienda reclama.
- `supplier_cache.json` — las respuestas del proveedor; una nueva ejecución las reutiliza (`--retry-errors` para volver a pedir las que fallaron).
