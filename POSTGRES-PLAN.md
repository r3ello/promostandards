# Plan: catálogo y estado de sincronización en Postgres

Estado de partida (working tree del 2026-08-17): 98 tests verdes, cachés de catálogo en
`data/*.json`, sin base de datos. Catálogo real: **1.278 productos**, ~943 ids importados.

Objetivo: mover a Postgres lo que necesita sobrevivir a un reinicio, **sin abandonar** la decisión
de arquitectura ya tomada: la fuente de verdad es Shopify + los metafields `ps_*`, no la BD.

---

## 1. Por qué una BD (y por qué no para cachear)

El argumento fuerte está en `SyncScheduler.runForEach()`, que recorre todos los productos
importados y reenvía inventario y precio sin saber si algo cambió:

```java
List<String> productIds = sync.listImportedProductIds();
for (String productId : productIds) { op.applyAsInt(productId); }
```

Con ~943 ids, cada uno a 4 llamadas SOAP (`catalog.aggregate`) más 2-3 a Shopify, una pasada de
inventario son **~3.700 SOAP y ~2.800 GraphQL cada 30 minutos**, escribiendo en su mayoría valores
idénticos a los que ya están. Eso no se arregla sin recordar qué se envió la última vez.

Del mismo hueco salen tres problemas más:

- **Los fulfillments se pueden duplicar.** El cursor de órdenes vive en un campo `volatile Instant`,
  así que cada reinicio retrocede 24 h y vuelve a llamar a `fulfillmentCreate` con los mismos envíos.
- **El match de PO es adivinanza.** PO del proveedor → orden de Shopify se resuelve con una búsqueda
  `name:<po>`. Una tabla de mapeo lo convierte en un hecho.
- **La búsqueda y el orden se quedan en la página.** El frontend filtra en local sobre lo cargado; el
  servidor no puede responder "todos los close-out por debajo de $50, ordenados por nombre".

**Lo que NO se mueve:** la caché de detalle por producto sigue en memoria (`TtlCache`). Es una caché
de llamadas upstream con 15 minutos de vida; meterla en Postgres añade un salto de red sin ganar
nada. Subir los JSON al repo también queda descartado: son datos del proveedor y de la tienda, no
código, y el índice de familias depende de metafields vivos de Shopify.

---

## 2. Qué posee la base de datos

| Dato | Fuente de verdad | Papel de Postgres | Si se pierde la BD |
|---|---|---|---|
| Identidad (qué producto de Shopify es qué id) | metafields `ps_product_id` / `ps_product_ids` | índice cacheado | se reconstruye desde Shopify |
| Catálogo (ids, títulos, vendor, close-out) | servicios de PaceSetter | espejo con TTL | se reconstruye del proveedor |
| Familias (variantes agrupadas) | Common Grouping + `ps_product_ids` | espejo del índice calculado | se reconstruye |
| Bookkeeping de sync (qué se envió) | **Postgres** | dueño | un re-push completo, luego incremental otra vez |
| Enlaces PO → orden, envíos ya enviados | **Postgres** | dueño | riesgo de fulfillments duplicados → **respaldar** |

Sólo las dos últimas filas son irremplazables, y sólo la última tiene una consecuencia peor que
trabajo desperdiciado. Ahí es donde apunta `pg_dump`.

---

## 3. Esquema

Una migración Flyway. Todas las tablas llevan `supplier_code` para que un segundo proveedor no
obligue a reescribir el esquema (hoy las credenciales se modelan globalmente; esto es la mitad
barata de arreglarlo más adelante).

### Espejo del catálogo

`src/main/resources/db/migration/V1__init.sql`

```sql
create table supplier_product (
    supplier_code        text        not null,
    product_key          text        not null,   -- upper(product_id): la clave de join en todas partes
    product_id           text        not null,   -- casing original del proveedor, para mostrar
    title                text,
    vendor               text,
    product_type         text,
    close_out            boolean     not null default false,
    sellable             boolean     not null default true,
    product_data_missing boolean     not null default false,
    first_seen_at        timestamptz not null default now(),
    last_seen_at         timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    primary key (supplier_code, product_key)
);

create index supplier_product_title_idx on supplier_product (supplier_code, lower(title));
create index supplier_product_sellable_idx on supplier_product (supplier_code, sellable, close_out);

-- Variantes agrupadas: una fila por miembro; el primary es miembro de sí mismo.
create table product_group_member (
    supplier_code     text not null,
    product_key       text not null,
    group_primary_key text not null,
    source            text not null,             -- 'common_grouping' | 'migration'
    primary key (supplier_code, product_key)
);
create index product_group_member_primary_idx
    on product_group_member (supplier_code, group_primary_key);
```

### Enlace con Shopify y estado de sincronización

```sql
-- Un producto migrado de Shopify puede cubrir varios ids del proveedor (N:1), así que el
-- enlace se indexa por producto del proveedor y apunta al producto de la tienda.
create table shopify_product_link (
    supplier_code text        not null,
    product_key   text        not null,
    shopify_gid   text        not null,
    handle        text        not null,
    source        text        not null,          -- 'app' | 'migration'
    canonical     boolean     not null default false,
    linked_at     timestamptz not null default now(),
    primary key (supplier_code, product_key)
);
create index shopify_product_link_gid_idx on shopify_product_link (shopify_gid);

create table sync_state (
    supplier_code        text        not null,
    product_key          text        not null,
    kind                 text        not null,   -- 'inventory' | 'price' | 'import'
    payload_hash         text,                   -- sha-256 de lo que realmente se envió
    last_success_at      timestamptz,
    last_attempt_at      timestamptz,
    consecutive_failures int         not null default 0,
    next_attempt_after   timestamptz,            -- backoff para fallos repetidos
    last_error           text,
    primary key (supplier_code, product_key, kind)
);
create index sync_state_due_idx
    on sync_state (supplier_code, kind, next_attempt_after);
```

### Órdenes y jobs

```sql
create table order_link (
    supplier_code      text not null,
    po_number          text not null,
    shopify_order_gid  text,
    shopify_order_name text,
    matched_at         timestamptz,
    primary key (supplier_code, po_number)
);

-- Clave de deduplicación de envíos ya convertidos en fulfillments. Esto es lo que impide
-- que un reinicio (que retrocede el cursor 24 h) cree duplicados.
create table order_shipment_pushed (
    supplier_code   text        not null,
    po_number       text        not null,
    shipment_key    text        not null,        -- tracking number + package id
    fulfillment_gid text,
    pushed_at       timestamptz not null default now(),
    primary key (supplier_code, po_number, shipment_key)
);

-- Reemplazo duradero del orderCursor en memoria de SyncScheduler.
create table job_watermark (
    job        text        primary key,
    cursor_at  timestamptz not null,
    updated_at timestamptz not null default now()
);

create table sync_run (
    id          bigserial primary key,
    job         text        not null,
    started_at  timestamptz not null,
    finished_at timestamptz,
    processed   int, succeeded int, failed int, skipped int,
    error       text
);
```

Todo se escribe con `insert … on conflict … do update`, así que repetir un escaneo es idempotente.
Usar `text`, nunca `varchar(n)`: los títulos del proveedor llevan comillas de pulgada y puntuación
arbitraria (`6" x 9.5" x 2" Crystal rectangle`) y el límite de longitud no aporta nada.

---

## 4. Cambios en el código

### Dependencias

Tres añadidos a `pom.xml`. Esto rompe la propiedad de "cero dependencias nuevas, compila offline":
la primera build tras el cambio necesita red (PowerShell con
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT`, como siempre). La build de Docker ya
resuelve contra la red, así que el servidor no se ve afectado.

```
org.springframework.boot:spring-boot-starter-jdbc   <!-- JdbcClient + HikariCP, sin ORM -->
org.postgresql:postgresql                           <!-- scope runtime -->
org.flywaydb:flyway-core + flyway-database-postgresql
```

Spring Data JDBC o JPA también valdrían, pero `JdbcClient` a secas mantiene los upserts explícitos
— y aquí casi todas las escrituras son merges `on conflict`, no guardados de entidad.

### Clases nuevas

| Clase | Responsabilidad |
|---|---|
| `db/CatalogRepository` | upsert de productos escaneados; consulta con búsqueda, orden, paginación y filtros de close-out/importado |
| `db/ProductGroupRepository` | reemplazar el índice de familias atómicamente; consultar la familia de un producto |
| `db/ShopifyLinkRepository` | id del proveedor → producto de la tienda; sustituye el `supplierIdIndex` en memoria |
| `db/SyncStateRepository` | leer trabajo pendiente, registrar éxito/fallo, guardar hashes y backoff |
| `db/OrderLinkRepository` | PO → orden, dedupe de envíos, watermarks de jobs |
| `sync/SyncDigest` | hashing estable de lo que se va a enviar (ver casos borde) |

### Clases modificadas

| Clase | Cambio |
|---|---|
| `CatalogTitleIndex` | persistir en `supplier_product` en vez de `data/catalog-titles.json`; mantener el build en background y el contrato `building/ready` |
| `CatalogGroupIndex` | persistir en `product_group_member` en vez de JSON; mismo disparador y misma vista |
| `SupplierProductScan` | **sin cambios**. Sigue siendo el único pase throttled que alimenta ambos índices |
| `CatalogSummaryService` | `listProductIds()` lee del espejo (con título, vendor, close-out e importado ya unidos) en vez de llamar upstream; mantiene el camino upstream como fallback si la BD no está disponible |
| `CatalogController` | `GET /products` gana `?q=&sort=&page=&size=&status=`; el endpoint de títulos queda redundante cuando la lista ya los trae |
| `ShopifySyncService` | escribir el enlace y `sync_state` tras cada push exitoso; resolver vía `ShopifyLinkRepository` con fallback al lookup por handle actual |
| `SyncScheduler` | iterar sólo el trabajo realmente pendiente (hash distinto, nunca enviado, o pasado su backoff); registrar un `sync_run`; tomar el cursor de órdenes de `job_watermark` |
| `OrderSyncService` | resolver la orden primero por `order_link`, luego por la búsqueda `name:<po>`, guardando lo que aprenda; saltar envíos ya presentes en `order_shipment_pushed` |
| `app.js` | búsqueda/orden/paginación en servidor; quitar el merge local de títulos y su polling |

---

## 5. Configuración y despliegue

### Config de la aplicación

Un flag decide si la BD se usa siquiera, para poder apagar todo el cambio en producción sin
recompilar.

```yaml
sync:
  persistence:
    enabled: ${SYNC_PERSISTENCE_ENABLED:false}   # false = comportamiento actual exacto

spring:
  datasource:
    url:      ${DB_URL:}
    username: ${DB_USER:}
    password: ${DB_PASSWORD:}
    hikari:
      maximum-pool-size: 10
      connection-timeout: 5000
  flyway:
    enabled: ${SYNC_PERSISTENCE_ENABLED:false}
    locations: classpath:db/migration
```

Con el flag apagado, excluir `DataSourceAutoConfiguration` para que un `DB_URL` ausente no pueda
tumbar el arranque. Con el flag encendido, una BD rota **debe** fallar ruidosamente al arrancar: un
sync silenciosamente sin BD reenviaría el catálogo entero.

### Variables de entorno nuevas

| Variable | Ejemplo | Notas |
|---|---|---|
| `SYNC_PERSISTENCE_ENABLED` | `true` | interruptor maestro; dejar en `false` hasta que la BD responda |
| `DB_URL` | `jdbc:postgresql://host.docker.internal:5432/promostandards` | ver la nota de red |
| `DB_USER` | `promostandards` | rol propio, no `postgres` |
| `DB_PASSWORD` | — | secreto; sólo en `.env`, nunca commiteado |

Añadir las cuatro a `.env.example` con placeholders. `.dockerignore` ya excluye `.env`, así que no
se filtra nada nuevo a la imagen.

### Llegar a Postgres desde el contenedor

La app corre en Docker; Postgres corre en el host. En Linux `host.docker.internal` no resuelve salvo
que se mapee explícitamente:

```yaml
    environment:
      SPRING_PROFILES_ACTIVE: prod
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - ./data:/app/data          # sigue haciendo falta: cachés JSON hasta que aterrice la fase 2
```

Dos ajustes en el servidor tienen que acompañar: Postgres debe escuchar en el bridge de Docker
(`listen_addresses`) y `pg_hba.conf` necesita una regla `scram-sha-256` para la subred de Docker
(`172.16.0.0/12`). **No** abrir el 5432 a internet: con el bridge basta.

### Creación de la base de datos

```sql
create role promostandards login password '…';
create database promostandards owner promostandards encoding 'UTF8';
```

Flyway crea y migra las tablas al primer arranque; no hace falta más DDL manual. Añadir un `pg_dump`
de esta base al respaldo que ya tengas del servidor — `order_shipment_pushed` es la única tabla cuya
pérdida tiene coste real.

---

## 6. Fases

Cada fase es desplegable por sí sola y deja la app funcionando. El orden está elegido para que el
cambio más arriesgado (escrituras a Shopify) llegue sólo después de probar el lado de lectura en
producción.

### Fase 1 — Fontanería
Dependencias, `V1__init.sql`, config del datasource, el flag, y un health check que reporte si la BD
responde. Sin cambios de comportamiento; el flag sigue apagado en prod hasta confirmar la conexión.

> **Listo cuando:** la app arranca con el flag encendido, `flyway_schema_history` muestra V1
> aplicada, y `/actuator/health` está en verde.

### Fase 2 — Espejo del catálogo (lectura) — **IMPLEMENTADA 2026-08-18**

Implementado: puerto `CatalogStore` + `JdbcCatalogStore`; `CatalogTitleIndex` escribe el espejo
(reutilizando su propio escaneo, sin pase extra) y `CatalogGroupIndex` persiste las familias;
`listProductIds()` lee del espejo con **fallback al proveedor** si la BD falta, está vacía o no
responde; `GET /api/catalog/products/search?q=&status=&sort=&asc=&page=&size=` busca/ordena/pagina en
SQL y responde 503 si no hay BD. Los JSON dejan de escribirse cuando la persistencia está encendida.

**Decisión tomada durante la implementación:** el frontend se queda filtrando en local. Con 1.278
productos el índice de títulos ya está en memoria del navegador y filtrar sin ida y vuelta al
servidor es más rápido que consultar por pulsación. El endpoint de búsqueda queda listo y probado
para cuando el catálogo crezca (o entre un segundo proveedor); cambiar el frontend entonces es
sustituir `filtered()` por una llamada con debounce.

El escaneo escribe en `supplier_product` y `product_group_member`; la lista del catálogo lee de
ahí, con búsqueda, orden y paginación en servidor. Los ficheros JSON dejan de escribirse. Todavía
no se escribe nada en Shopify, así que el radio de impacto es sólo la consola.

> **Listo cuando:** el número de filas coincide con el catálogo vendible (1.278), buscar un nombre
> de producto responde al instante, y reiniciar el contenedor no obliga a reconstruir.

### Fase 3 — Estado de sync y scheduler incremental — **IMPLEMENTADA 2026-08-18**

Implementado: `SyncDigest` (hash de los valores **salientes**, ordenado por SKU, con la location y la
divisa dentro); puerto `SyncStateStore` + `JdbcSyncStateStore` (hash, contador de fallos, backoff
exponencial 5min→12h, historial en `sync_run`); `ShopifySyncService.refresh(id, kind, dryRun, force)`
con outcomes `PUSHED / UNCHANGED / WOULD_PUSH / BACKING_OFF / FAILED / STATE_UNAVAILABLE`; el
scheduler recorre y resume por outcome; `sync.schedule.dry-run` (`SYNC_SCHEDULE_DRY_RUN`).

**Nota sobre el ahorro real:** las llamadas SOAP al proveedor **siguen ocurriendo** — no existe un
endpoint "qué cambió" ni para inventario ni para precios, así que hay que leer el producto para saber
si cambió. Lo que desaparece son las escrituras a Shopify: de ~2.800 mutaciones por pasada a ~0 en
régimen estable. Reducir también el lado SOAP requeriría cachear el mapeo SKU→variante, que no está
en este plan.

**Bloqueo entre scheduler y sync manual:** resuelto con locks en proceso (32 stripes por id), no con
advisory locks de Postgres, porque mantener una transacción abierta durante llamadas de red viola
otro caso borde del plan. Es correcto con **una** instancia; una segunda necesitaría un lease en BD.

Registrar hashes y enlaces en cada push; enseñar al scheduler a saltar los productos cuyo hash no
cambió. Ejecutarlo una vez con el scheduler aún desactivado y loguear lo que *haría* — ese dry run
es la prueba real del hashing.

> **Listo cuando:** una segunda pasada consecutiva reporta ~0 productos pendientes, y cambiar
> `markup-percent` vuelve a marcar como pendientes todas las filas de precio.

### Fase 4 — Órdenes — **IMPLEMENTADA 2026-08-18**

Implementado: puerto `OrderStore` + `JdbcOrderStore` (`order_link`, `order_shipment_pushed`,
`job_watermark`); dedupe **por envío** (clave `salesOrderNumber|trackingNumber`) antes de cada
`fulfillmentCreate`; resolución de PO por id recordado con caída a la búsqueda `name:` y corrección
automática si el id guardado ya no resuelve; watermark de órdenes persistido en vez de vivir en un
campo en memoria. `OrderSyncResult` gana `shipmentsSkipped`, que es la métrica que demuestra el
dedupe funcionando tras un reinicio. Nueva query `ORDER_BY_ID`, validada contra 2026-04 con
`shopify-dev-mcp`.

**Orden de escritura elegido:** se registra el envío *después* de que Shopify acepte el
`fulfillmentCreate`. Un fallo entre ambos duplicaría en la siguiente pasada; al revés se arriesgaría
a no cumplimentar nunca. Una notificación duplicada se reconcilia; una que no se envía, no.

`order_link`, dedupe de envíos y el watermark duradero. Aquí se cierra el agujero actual de
fulfillments duplicados al reiniciar.

> **Listo cuando:** reiniciar la app a mitad de ventana no crea ningún fulfillment duplicado,
> verificado contra una orden de prueba.

### Fase 5 (opcional) — Snapshots
Guardar el detalle ensamblado como `jsonb` para que la consola pinte filas completas al instante
desde la BD y refresque upstream en background. Sólo merece la pena si tras la fase 2 la consola
sigue sintiéndose lenta.

> **Listo cuando:** una carga en frío pinta filas completas sin ninguna llamada upstream.

---

## 7. Casos borde

Los que marcan la diferencia entre una migración que funciona y una que corrompe estado en
silencio. Agrupados por dónde muerden.

### ESQUEMA · Los ids del proveedor difieren en mayúsculas/minúsculas
El código ya normaliza a mayúsculas en varios sitios: los ids de la migración parseados de la base
legacy no coinciden en casing con el feed del proveedor. Una PK ingenua sobre `product_id` crearía
dos filas para `sample-001` y `SAMPLE-001`.
**Solución:** indexar todas las tablas por `product_key = upper(product_id)` y guardar el casing
original en una columna aparte sólo para mostrar. Nunca hacer join por `product_id`.

### SYNC · Un cambio de política de precios debe invalidar el hash
Si el hash cubre el precio neto del proveedor, subir `markup-percent` de 40 a 45 cambia lo que
debería estar en Shopify pero no el hash — y el scheduler saltaría todos los productos, dejando los
precios viejos publicados. Lo mismo aplica al redondeo y al suelo MAP.
**Solución:** hashear los valores **calculados y salientes** (SKU → precio final), no la entrada del
proveedor, e incluir en el digest una firma de la política de precios y la divisa objetivo.

### SYNC · Los hashes tienen que ser estables respecto al orden
El orden de las variantes que manda el proveedor no está garantizado entre llamadas. Hashear una
lista sin ordenar produce un digest distinto para datos idénticos, y cada pasada reenvía todo — justo
el problema que esto pretende resolver.
**Solución:** ordenar por SKU, orden de campos fijo y formato numérico canónico
(`BigDecimal.toPlainString()`, sin locale), y luego SHA-256. Incluir el id de la location de
inventario: cambiar de location debe forzar un re-push.

### SYNC · Un push fallido no puede parecer un éxito
Si `last_success_at` y el hash se escriben antes de que vuelva la llamada a Shopify, un fallo deja la
fila marcada como sincronizada y el producto no reintenta jamás.
**Solución:** escribir el hash sólo después de que la mutación devuelva éxito y sin `userErrors`. En
caso de fallo, incrementar `consecutive_failures` y fijar `next_attempt_after` con backoff
exponencial, para que un producto permanentemente roto no se coma cada pasada.

### ÓRDENES · Reprocesar una ventana de envíos duplica fulfillments
El cursor de hoy es un campo en memoria, así que un reinicio retrocede 24 h y vuelve a llamar a
`fulfillmentCreate` para envíos ya cumplimentados. Un watermark duradero por sí solo no lo arregla:
el proveedor también puede reenviar un envío dentro de una ventana ya procesada.
**Solución:** deduplicar por el envío en sí (`order_shipment_pushed`, con clave PO + tracking number
+ package id), no sólo por la ventana temporal. El watermark abarata las pasadas; la clave de dedupe
las hace seguras.

### CONSISTENCIA · Los productos de la tienda se pueden borrar por detrás
Un `shopify_gid` cacheado queda obsoleto cuando alguien borra o despublica el producto en el admin de
Shopify. El push falla, y un reintento ingenuo repite el fallo para siempre.
**Solución:** ante un error de "no encontrado", borrar la fila de enlace, re-resolver por handle y
luego por el índice de metafields, y sólo entonces tratarlo como fallo real. Mantener el lookup por
handle actual como camino de respaldo en vez de confiar ciegamente en la BD.

### CONSISTENCIA · Productos que desaparecen del feed
Un id que se cae de `getProductSellable` no debe borrarse del espejo: puede seguir existiendo en
Shopify, y borrar la fila perdería su estado de sync y su enlace.
**Solución:** el escaneo marca `sellable = false` y deja `last_seen_at` intacto. Nunca propagar una
desaparición del proveedor a un borrado en Shopify; mostrarlo en la consola.

### RUNTIME · Nunca mantener una transacción abierta durante una llamada de red
La forma natural pero equivocada es: abrir transacción, llamar a PaceSetter y Shopify, escribir el
resultado, commit. Esas llamadas tardan segundos; el pool tiene diez conexiones y un pool de 12
hilos alimentándolo.
**Solución:** leer lo necesario, cerrar la transacción, hacer el trabajo de red, y luego abrir una
transacción de escritura corta. Ninguna conexión debe estar retenida mientras hay una llamada SOAP o
GraphQL en vuelo.

### RUNTIME · El scheduler y un sync manual pueden chocar en el mismo producto
Un usuario pulsando "Sync inventory" mientras el cron recorre el catálogo produce dos pushes
concurrentes del mismo producto y dos escrituras conflictivas en `sync_state`.
**Solución:** tomar un advisory lock de Postgres por producto
(`pg_try_advisory_lock(hashtext(key))`) y saltar si ya está tomado. Barato, se libera solo, sin
tabla extra.

### OPS · Que la BD esté caída no puede cambiar el comportamiento en silencio
Si el espejo no responde y el scheduler no puede leer hashes, tratar "sin estado" como "enviar todo"
convierte una caída en una tormenta de escrituras sobre el catálogo completo.
**Solución:** el camino de lectura degrada (la lista del catálogo cae al upstream, como hoy); el
camino de escritura **se detiene**. Una pasada de sync que no alcanza la BD loguea y sale, en vez de
continuar sin estado.

### OPS · Las marcas de tiempo del proveedor no son UTC
Las respuestas de PaceSetter llevan offsets locales. Mezclarlas con `now()` en comparaciones produce
ventanas silenciosamente desfasadas por horas — que, para un cursor de órdenes, significa envíos
perdidos o duplicados.
**Solución:** `timestamptz` en todas partes, normalizar a `Instant` al entrar, y elegir un único
reloj para los watermarks (el de la aplicación) en vez de mezclar hora de app y hora de BD.

### OPS · Las migraciones son inmutables una vez aplicadas
Editar `V1__init.sql` después de que haya corrido en el servidor hace que Flyway falle por checksum,
normalmente en el peor momento.
**Solución:** cada cambio posterior al primer despliegue es un fichero nuevo `V2__…`, `V3__…`. Tratar
las migraciones aplicadas como historia.

### OPS · Dos instancias de la app duplicarían los pushes
Nada en el diseño impide que un segundo contenedor corra los mismos cron jobs. Flyway se protege con
su propio lock, pero el scheduler no.
**Solución:** por ahora, correr una sola instancia y dejarlo escrito en `DEPLOY.md`. Si algún día
hace falta una segunda, proteger cada job con un advisory lock retenido durante toda la pasada.

---

## 8. Testing

No hay Docker en la máquina de desarrollo, así que Testcontainers no es una opción. Eso condiciona
todo el enfoque.

- **Mantener los 98 tests actuales offline y verdes.** Con interfaces de repositorio y fakes en
  memoria, la lógica de "¿toca sincronizar esto?", el hashing y el dedupe de órdenes se testean sin
  base de datos. Ahí es donde vive el riesgo real, y queda cubierto del todo.
- **No testear comportamiento de Postgres sobre H2.** `on conflict`, `jsonb` y `timestamptz` difieren
  lo bastante como para que una suite verde en H2 dé falsa confianza sobre el SQL que se despliega.
- **Condicionar los tests contra BD real a una variable de entorno.** Anotarlos con
  `@EnabledIfEnvironmentVariable(named = "TEST_DB_URL", matches = ".+")` para que corran contra el
  Postgres del servidor (o uno local que instales luego) y se salten en silencio en el resto.
- **Probar la migración en sí** corriendo Flyway contra una base desechable antes de desplegar: una
  migración que falla al arrancar en el servidor se lleva la app por delante.

---

## 9. Despliegue y rollback

1. Crear el rol y la base en el servidor; confirmar que el contenedor llega (un `psql` desde el host
   y una prueba de conexión desde el contenedor).
2. Desplegar con `SYNC_PERSISTENCE_ENABLED=false`. No cambia nada; sólo valida la build y la imagen.
3. Encender el flag y reiniciar. Flyway migra; la app se comporta como antes pero ya escribe el
   espejo.
4. Abrir la consola, dejar que el escaneo del catálogo corra una vez, y contrastar el número de filas
   con los 1.278 productos vendibles.
5. Ejecutar el dry run del scheduler y leer el log: la primera pasada reporta todo como pendiente, la
   segunda reporta ~nada. Ese contraste es la prueba de que el incremental funciona.
6. Sólo entonces poner `SYNC_SCHEDULE_ENABLED=true`.

**Rollback** es poner `SYNC_PERSISTENCE_ENABLED=false` y reiniciar. La app vuelve a los índices en
memoria y a las cachés JSON; la base queda intacta y se puede reactivar después. Esto sólo es cierto
si se conservan los caminos de fallback de la fase 2 en vez de borrarlos — conviene resistir la
tentación de limpiarlos hasta que la BD lleve unas semanas corriendo.

---

## 10. Una decisión pendiente antes de la fase 1

Si la lista de productos de la consola debe seguir funcionando con Postgres caído. Mantener el
fallback al upstream cuesta algo de código duplicado, pero hace que un problema de base de datos
degrade la consola en vez de romperla. Siendo la herramienta que tu cliente usa a diario, parece que
compensa — pero es un juicio, no una necesidad técnica.
