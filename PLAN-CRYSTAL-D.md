# Segundo proveedor: Crystal D

Estado: **plan** (2026-09-18). Nada implementado.

Objetivo: sincronizar productos de Crystal D en la misma tienda que PaceSetter (catálogo, precios,
stock, imágenes, descuentos y seguimiento de pedidos) y, más adelante, enviarle pedidos por API.
PaceSetter tiene que seguir funcionando exactamente igual durante y después del cambio.

## 1. Qué publica Crystal D (verificado 2026-09-18)

Registro PromoStandards, código **`crystald`**. Los 8 servicios están en producción y los 8 sirven
su WSDL en `https://api.crystal-d.com/promostandards/<servicio>?wsdl`, con los namespaces estándar.

- **Ya cubiertos** (misma versión y mismo WSDL que PaceSetter; sólo cambian URL y credenciales):
  Pricing & Configuration 1.0.0 (`productConfiguration`), Media Content 1.1.0 (`media`),
  Order Status 1.0.0 (`orderstatus`), Order Shipment Notification 1.0.0 (`ordershipmentnotification`).
- **Otra versión** (hacen falta WSDL, codegen y cliente nuevos):
  - **Product Data 2.0.0** (`productdata`). Nosotros usamos la 1.0.0 y Crystal D sólo publica la 2.0.0.
    La estructura es muy parecida (`ProductPartArray`, `ColorArray`, `ApparelSize`, `Dimension`).
    Añade `primaryImageUrl`, grupos de precio, `LocationDecorationArray` y `lastChangeDate`. Los
    errores llegan como `ServiceMessageArray`, no como `ErrorMessage`.
  - **Inventory 2.0.0** (`inventory`). Nosotros usamos la 1.2.1, y aquí el cambio es mayor:
    `PartInventory` con `partColor`/`labelSize`/`mainPart`, la cantidad como `Quantity{uom,value}`,
    stock por almacén (`InventoryLocationArray`) con disponibilidad futura, y errores como
    `ServiceMessageArray`. Aun así cabe en nuestro modelo `InventoryLevels` (§7).
- **Nuevos**: **Purchase Order 1.0.0** (`purchaseorder`: `sendPO` y `getSupportedOrderTypes`) e
  Invoice 1.0.0 (`invoice`, no hace falta por ahora).
- **No hay entorno de pruebas**: el `TestURL` del registro es la misma URL de producción. Es
  irrelevante para las lecturas, pero **cada `sendPO` sería un pedido real** (§9).

WSDL oficiales, en los zips del registro:
- `https://promostandards.org/wp-content/uploads/2025/07/ProductData2-0-0-1.zip` (carpeta `2.0.0/`)
- `https://promostandards.org/wp-content/uploads/2025/07/InventoryV2Final-1-1.zip` (carpeta
  `WSDL/2.0.0RC4/`; el namespace es `Inventory/2.0.0/`, el mismo que sirve Crystal D)
- `https://promostandards.org/wp-content/uploads/2025/07/purchaseOrder-1.0.0-1.zip` (carpeta `wsdl/1.0.0/`)

## 2. Qué hay en la tienda hoy (leído en vivo, sin escribir, 2026-09-18)

- **5.098 productos, todos con vendor `TrophyPartner`**: el vendor no dice de qué proveedor es cada uno.
- **1.154 se identifican como PaceSetter**: 676 sólo por `custom.ps_supplier`, 132 sólo por
  `trophy_sync.vendor` y 346 por los dos. Todos tienen al menos uno de los dos.
- **3.944 no tienen ningún metafield de proveedor.** Ninguno se identifica como Crystal D. Si la
  tienda ya vende artículos de Crystal D, están ahí sin nada que los señale, y habría que
  encontrarlos antes de crear nada (§3.3).
- El índice de la app (`IMPORTED_PRODUCTS`, `tag:promostandards`) indexa cada producto **por id a
  secas**, sin mirar el proveedor.

## 3. Fase 0: decisiones y requisitos (bloqueante)

1. **Credenciales PromoStandards de Crystal D** (cuenta de distribuidor), y si tienen cuenta o modo
   de pruebas para `sendPO`.
2. **Qué catálogo**: ¿todo lo vendible o un lote revisado? Recomendado: un lote revisado, como el de
   PaceSetter del 2026-09-16.
3. **¿La tienda ya vende artículos de Crystal D?** Si es así, ¿cómo se reconocen (SKU, título)? Es
   para no duplicarlos (§2).
4. **Forma de los productos creados.** Lo propuesto es lo mismo que con PaceSetter: `DRAFT`, handle
   `p-<n>-<nombre>` siguiendo la numeración de la tienda, vendor `TrophyPartner`, etiqueta
   `promostandards`.
5. **Prefijo de SKU.** No puede ser `PS`, que es el de PaceSetter. Tampoco conviene `CD`: PaceSetter
   tiene ids que empiezan por CD (`CD950ABS`, cuyo SKU es `PSCD950ABS`) y se prestaría a confusión.
   Lo decide el cliente (por ejemplo `CRD`), y se comprueba contra los SKU de la tienda antes de
   crear el primero.
6. **Entrada del metaobjeto** `promo_standard_supplier` con handle `crystal-d` en la tienda. Se crea
   a mano o con Matrixify; la app sólo la referencia.
7. **Precio.** ¿Publica Crystal D precio de venta (`priceType=List`)? Si no, qué markup aplicar. El
   redondeo a .99 y la escalera de descuentos se mantienen.
8. **Pedidos.** ¿Se usará `sendPO`? ¿Quién graba? Son las mismas preguntas que en
   `PLAN-PEDIDOS-PACESETTER.md` §2.

## 4. Principio de orden: primero "varios proveedores" con uno solo

El cambio a varios proveedores (§6) se hace y se despliega **con PaceSetter como único proveedor y
sin cambiar su comportamiento**: la misma configuración sigue valiendo, los 320 tests siguen verdes
y una pasada programada se vigila en producción. Sólo después se añade Crystal D. Así, si algo se
rompe, se sabe si fue el refactor o el proveedor nuevo.

## 5. Fase 1: descubrimiento de datos (sin tocar la app)

Con las credenciales, preguntar a Crystal D directamente y apuntar sus rarezas.

- **Ampliar `query_service.py` del toolbox** (una opción nueva, no una copia): Product Data 2.0.0
  (hoy sólo conoce el namespace 1.0.0), `getProductSellable`, y el resumen de Inventory 2.0.0 por
  `PartInventory` (hoy busca `ProductVariationInventory`, que es el nombre de la 1.2.1).
- **Qué mirar**:
  - cuántos productos vende y qué forma tienen los ids;
  - si rellenan color (`colorName`/`hex`) o mandan "N/A" como PaceSetter;
  - si sus imágenes llevan `partId`;
  - qué tipos de precio publican (List/Net) y sus tramos;
  - si el stock viene por pieza y por almacén;
  - las zonas de decoración (PPC);
  - si Inventory responde por familias enteras, como PaceSetter.
- **Resultado**: `reports/crystald-discovery-<fecha>.md`, con 10–20 productos representativos, más
  respuestas reales (sin credenciales) guardadas como fixtures para los tests de la fase 3.
- **Criterio de salida**: saber, regla por regla (§6.7), si Crystal D la necesita.

## 6. Fase 2: la app pasa a "varios proveedores" (sólo con PaceSetter)

### 6.1 Configuración

Hoy las credenciales son globales y cada servicio tiene una sola URL
(`PromoStandardsProperties`); `sync.supplier-code`, `sync.supplier-metaobject`,
`create-products.sku-prefix` y `sync.pricing` también son globales. Pasan a un bloque por proveedor:

```yaml
promostandards:
  default-supplier: pacesetter
  suppliers:
    pacesetter:
      code: PaceSetter                 # trophy_sync.vendor, handle ps-<code>-<id>, supplier_code en BD
      credentials: { id: "${PROMOSTANDARDS_CREDENTIALS_ID:}", password: "${PROMOSTANDARDS_CREDENTIALS_PASSWORD:}" }
      services:
        inventory:      { mode: soap, ws-version: 1.2.1, endpoint-url: ... }
        product-data:   { mode: soap, ws-version: 1.0.0, endpoint-url: ... }
        # pricing, media, order-status, order-shipment ...
      store:
        metaobject-handle: pace-setter
        sku-prefix: PS
        pricing: { strategy: SUPPLIER_LIST, markup-percent: 40, map-floor: true }
    crystald:
      code: CrystalD
      credentials: { id: "${CRYSTALD_ID:}", password: "${CRYSTALD_PASSWORD:}" }
      services:
        product-data:   { mode: soap, ws-version: 2.0.0, endpoint-url: https://api.crystal-d.com/promostandards/productdata }
        inventory:      { mode: soap, ws-version: 2.0.0, endpoint-url: https://api.crystal-d.com/promostandards/inventory }
        pricing:        { mode: soap, ws-version: 1.0.0, endpoint-url: https://api.crystal-d.com/promostandards/productConfiguration }
        media:          { mode: soap, ws-version: 1.1.0, endpoint-url: https://api.crystal-d.com/promostandards/media }
        order-status:   { mode: soap, ws-version: 1.0.0, endpoint-url: https://api.crystal-d.com/promostandards/orderstatus }
        order-shipment: { mode: soap, ws-version: 1.0.0, endpoint-url: https://api.crystal-d.com/promostandards/ordershipmentnotification }
        purchase-order: { mode: stub, ws-version: 1.0.0, endpoint-url: https://api.crystal-d.com/promostandards/purchaseorder }
      store:
        metaobject-handle: crystal-d
        sku-prefix: CRD                # pendiente de §3.5
        pricing: { ... }               # pendiente de §3.7
```

- **Compatibilidad**: si no hay `suppliers`, las claves antiguas (`promostandards.credentials`,
  `promostandards.<servicio>`, `sync.supplier-code`…) forman el proveedor `pacesetter`. Así el `.env`
  del servidor sigue valiendo sin cambios.
- **Lo que sigue siendo de la tienda y queda global**: numeración de handles, vendor `TrophyPartner`,
  estado `DRAFT`, imágenes, crons y `create-products.enabled`.

### 6.2 Clientes SOAP por proveedor

- Hoy hay un bean por servicio (`XxxSoapConfig`, `@ConditionalOnProperty mode=soap`). Pasa a haber
  un **registro de clientes por (proveedor, servicio)**. Crea cada puerto con el mismo
  `JaxWsProxyFactoryBean` de hoy, parametrizado con la URL y los timeouts del proveedor, y elige la
  implementación por `ws-version`: Inventory `1.2.1` → `SoapInventoryClient`, `2.0.0` →
  `SoapInventoryClientV2`; Product Data `1.0.0` → `SoapProductDataClient`, `2.0.0` →
  `SoapProductDataClientV2`. El modo `stub` sigue existiendo por proveedor, para los tests.
- Los `XxxService` reciben el proveedor y ponen **sus** credenciales y **su** `wsVersion`.
- Pedir un servicio que un proveedor no publica (por ejemplo `sendPO` a PaceSetter) da un error
  claro, no un 502 genérico.

### 6.3 Identidad en la tienda

- **Regla: un producto de la tienda es de un solo proveedor.** `ProductGroupService` rechaza agrupar
  ids de proveedores distintos.
- **El proveedor de un producto** sale de `trophy_sync.vendor`; si falta, de `custom.ps_supplier`; y
  si faltan los dos, se toma el proveedor por defecto (PaceSetter). Verificado: los 1.154 de
  PaceSetter llevan al menos uno de los dos. `IMPORTED_PRODUCTS` tiene que leerlos, y la consulta se
  valida con `tools/validate_shopify_graphql.py`.
- **Todo lo que hoy va por id a secas pasa a ir por (proveedor, id)**:
  - el índice `supplierIdIndex`, `recentlyCreated` e `isImported` de `ShopifySyncService`;
  - la unión de `ForeignProductSync`;
  - la caché de detalle del catálogo.
- **Metafields: ninguno nuevo.** `custom.ps_product_id(s)`, `custom.promo_standard_id` y
  `trophy_sync.vendor_sku` siguen guardando el id tal cual; el proveedor lo da el producto. Así no
  hay que reescribir los 1.154 productos de PaceSetter.

### 6.4 Base de datos y cachés

- Seis de las ocho tablas ya llevan `supplier_code` en la clave primaria (V1 lo dejó previsto). Los
  `Jdbc*Store` dejan de fijarlo al arrancar (`props.supplierCode()`) y lo reciben en cada llamada.
- **Migración `V2`**: `job_watermark` (clave `job`) y `sync_run` no tienen `supplier_code`. Se añade
  con valor por defecto `'PaceSetter'` para las filas existentes, y la clave de `job_watermark` pasa
  a ser `(supplier_code, job)`.
- **Cachés e índices, uno por proveedor**: `SupplierProductScan`, `CatalogTitleIndex`,
  `CatalogGroupIndex`, `PendingDataIndex` y las TTL de `CatalogSummaryService`. Sus ficheros van a
  `data/<proveedor>/…`, y los de PaceSetter se quedan donde están para no reconstruirlos.

### 6.5 REST, consola y herramientas

- Los endpoints actuales aceptan `?supplier=`, que por defecto es `pacesetter`: nada de lo que hay
  deja de funcionar. Afecta a `/api/inventory`, `/api/products`, `/api/pricing`, `/api/media`,
  `/api/order-*`, `/api/catalog/*`, `/api/sync/*` y `/api/discounts/*`.
- `GET /api/suppliers`: los proveedores configurados y qué servicios tiene cada uno.
- **Consola**: un selector de proveedor encima de la tabla; todo lo demás funciona igual por
  proveedor, incluida la carga perezosa por página. El panel de sync programado muestra el resultado
  de cada proveedor.
- `tools/simulate_store_sync.py` y `tools/sync_store_catalog.py` reciben `--supplier`.

### 6.6 Scheduler

- Las pasadas de inventario, precio y pedidos recorren los proveedores uno tras otro. **Un proveedor
  que falla no para al otro.** El backoff y el digest ya son por `supplier_code`.
- `GET /api/sync/schedule` informa por proveedor, y `POST /schedule/{job}` acepta `?supplier=`.
- `fetch-threads` pasa a ser por proveedor: cada uno tolera una carga distinta.

### 6.7 Reglas de PaceSetter: cuáles se quedan generales

| Regla (dónde) | Decisión |
|---|---|
| `"N/A"`/`"NULL"` como vacío (`CatalogService.norm`) | General: no hace daño a nadie |
| Fotos sin `partId`: la galería va a la variante del id pedido (`CatalogService`) | General: el código ya usa `partId` cuando viene; confirmar en la fase 1 |
| Unión de la familia por Inventory (`ForeignProductSync`) | General, salvo que la fase 1 diga otra cosa |
| Pendientes: sin respuesta de Inventory = no listo (`PendingDataIndex`) | General |
| Nombre de la variante sin color (`distinguishingLabels`) | General |
| Handle sin medidas (`StoreHandle`) | General: es regla de la tienda |
| SKU `PS` + part id (`VariantSku`) | **Por proveedor** (`store.sku-prefix`) |
| Precio de venta `SUPPLIER_LIST`, markup, suelo MAP (`PricingPolicy`) | **Por proveedor** (`store.pricing`) |
| `productIDtype=Supplier` fijo (`SoapInventoryClient`) | Se queda en el cliente 1.2.1; la 2.0.0 no lo tiene |

## 7. Fase 3: clientes Product Data 2.0.0 e Inventory 2.0.0

- **WSDL vendorizados**: `src/main/resources/wsdl/product-v2/` y `wsdl/inventory-v2/`, sacados de
  los zips del §1.
- **`pom.xml`**: dos perfiles nuevos, activados como los demás por la existencia del WSDL. Paquetes
  propios, para que no choquen con los de la 1.x:
  - `…/ProductDataService/2.0.0/` → `productdata.soap.v2` (más `.shared`, `.xsstl` y `.iso4217`)
  - `…/Inventory/2.0.0/` → `inventory.soap.v2` (más `.shared` y `.xsstl`)
- **`SoapProductDataClientV2`**: traduce al modelo `Product` que ya existe.
  - `productName`, `description`, `productBrand`, categorías y `relatedProducts`, igual que en la 1.0.0.
  - Por pieza: `partId`, descripción, `primaryColor` (su `Color.colorName` o, si falta, el primero
    de `ColorArray`), tallas de `ApparelSize.labelSize`, peso y unidad de `Dimension`.
  - Las cuatro operaciones: `getProduct`, `getProductSellable`, `getProductCloseOut` y
    `getProductDateModified`.
  - Un `ServiceMessageArray` con severidad `Error` se convierte en `PromoStandardsClientException`.
- **`SoapInventoryClientV2`**: traduce al modelo `InventoryLevels`.
  - `partId` y `partDescription` directos; `quantityAvailable` = `Quantity.value` (el total; el
    detalle por almacén no se usa por ahora); color = `partColor`; talla = `labelSize`; selección =
    `attributeSelection`.
  - `partBrand` y `entryType` quedan en null.
  - También `getFilterValues`.
- **Tests**: `SoapProductDataClientV2Test` y `SoapInventoryClientV2Test`, sobre objetos generados
  como los `Soap*ClientTest` actuales y con las respuestas reales de la fase 1.
- **PPC, Media, Order Status y Order Shipment** usan los clientes actuales con las URLs de Crystal D.
  La fase 1 confirma que sus respuestas se traducen bien.

## 8. Fase 4: alta de Crystal D

1. Configuración y credenciales en el `.env` de **dev** (despliegue, tienda y base de datos de dev,
   con su propia app de Shopify: `SHOPIFY-EMBEDDED.md`, "Dos entornos").
2. Crear el metaobjeto `crystal-d` en la tienda (§3.6).
3. `simulate_store_sync.py --supplier crystald`: cuántos son nuevos y cuántos corren riesgo de
   duplicarse, comparando también por nombre con los 3.944 productos sin proveedor (§2).
4. **Primer lote revisado**, creado en `DRAFT` en la tienda de dev
   (`sync_store_catalog.py --supplier crystald --create @ids.txt`). Se revisa a mano: variantes,
   SKU, fotos, precio y descuentos.
5. Una pasada programada en **dry run** sólo para Crystal D; luego, real.
6. Repetir en prod.

## 9. Fase 5 (opcional): pedidos por API con `sendPO`

- Es el botón de `PLAN-PEDIDOS-PACESETTER.md`, con el paso 3 hecho por API en vez de por email:
  - WSDL `wsdl/po/` sacado de `purchaseOrder-1.0.0-1.zip`; paquetes `po.soap`, `po.soap.shared`,
    `.iso4217`, `.iso20022` y `.xsstl`.
  - Primero `getSupportedOrderTypes`; para piezas grabadas, `orderType=Configured`, con el texto en
    `Configuration › LocationArray › DecorationArray › Artwork › TypesetArray`.
- **Cada `sendPO` es un pedido real**: no hay URL de pruebas. No se llama a Crystal D hasta tener su
  confirmación de cómo probar (§3.1). Los tests usan sólo el stub.
- **Requisito previo: los arreglos del §4 de `PLAN-PEDIDOS-PACESETTER.md`.** Con dos proveedores, una
  orden puede mezclar líneas de PaceSetter y de Crystal D. Cumplir sólo las líneas de cada proveedor
  deja de ser una mejora y pasa a ser obligatorio: hoy el tracking de uno marcaría enviada la orden
  entera.

## 10. Pruebas

- **Refactor (fase 2)**: los 320 tests actuales, verdes y **sin cambiar lo que comprueban**; sólo
  cómo se construyen los objetos.
- **Tests nuevos**:
  - la configuración antigua se lee como el proveedor `pacesetter`;
  - el mismo id en dos proveedores no choca en el índice (con el `ShopifyHttp` falso);
  - agrupar productos de proveedores distintos se rechaza;
  - un proveedor que falla en el scheduler no para al otro;
  - prefijo de SKU y precio por proveedor;
  - `?supplier=` por defecto;
  - la migración `V2` (en los tests de base de datos);
  - la traducción de los clientes V2.
- Validar contra el esquema real todas las consultas GraphQL que se toquen
  (`tools/validate_shopify_graphql.py`).

## 11. Despliegue

- Siempre **dev primero** y después prod.
- Variables nuevas: `CRYSTALD_ID` y `CRYSTALD_PASSWORD`. Las URLs van en el YAML: son públicas.
- **Marcha atrás**: quitar el bloque `crystald` lo desactiva, y PaceSetter no se entera. La
  migración `V2` sólo añade columnas con valor por defecto, así que el código anterior sigue
  funcionando sobre ella.
- El volumen `data/` recibe `data/crystald/…`.

## 12. Riesgos

- **El refactor toca casi todo el paquete `sync`.** Se mitiga con la fase 2 sin cambio de
  comportamiento, desplegada y vigilada antes de añadir nada.
- **Duplicados** con artículos de Crystal D que la tienda ya venda sin identificar (§2, §3.3).
- **Rarezas desconocidas de Crystal D.** Para eso está la fase 1, antes de escribir el cliente.
- **Ids repetidos entre proveedores.** Cubierto por el índice (proveedor, id) y su test.
- **Carga sobre los proveedores**: dos catálogos en la misma pasada; por eso `fetch-threads` va por
  proveedor.
- **`sendPO` contra producción** (§9).

## 13. Esfuerzo orientativo

Es una estimación de orden de magnitud, que la fase 1 puede mover:

- Fase 0: depende del cliente y de Crystal D.
- Fase 1: 1–2 días.
- **Fase 2: 1–2 semanas.** Es la parte grande, y se hace una sola vez: el tercer proveedor ya sería
  sobre todo configuración.
- Fase 3: 2–4 días.
- Fase 4: 2–3 días, más la revisión del lote.
- Fase 5: alrededor de 1 semana, contando el botón de pedidos.
