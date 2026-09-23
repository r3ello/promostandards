# Pedidos a PaceSetter desde Shopify

Estado: **fase 1 empezada** (plan 2026-09-18; actualizado 2026-09-22: las dos entradas y la seguridad
del botón, §3). Hecho el 2026-09-22 y **sólo de lectura**: la lista de pendientes, la vista previa y
la pestaña Orders de la consola (`SupplierOrderService`, `GET /api/orders/pacesetter-pending` y
`GET /api/orders/{orderId}/pacesetter-po`), más el **email ya redactado** —
`GET /api/orders/{orderId}/pacesetter-po/email`, plantilla en `email/pacesetter-po.html` y
destinatarios en `orders.pacesetter.*` (`SupplierOrderEmail`). **El envío también está hecho**
(`POST /api/orders/{orderId}/pacesetter-po`, `SupplierOrderMailer` sobre `spring-boot-starter-mail`,
la primera dependencia nueva del proyecto): manda el email y marca la orden. Sigue **apagado**
(`orders.pacesetter.enabled=false` y sin `spring.mail.host`), y falta el buzón real del cliente y el
formato definitivo, que depende de un pedido real de PaceSetter (§2.4) y de la plantilla de
personalización, que todavía no se adjunta.

Objetivo: preparar el pedido a PaceSetter con los datos de la orden de Shopify — sin que nadie los
copie a mano —, desde la propia orden o desde la lista de lo que falta por enviar, y dejar la orden
unida al PO, para que el estado y el tracking que ya leemos de PaceSetter vuelvan solos a esa orden.

## 1. Cómo se le pide hoy a PaceSetter (investigado 2026-09-18)

**No hay API para enviarle pedidos.** El canal oficial es el email.

- En el registro de PromoStandards (`.../companies/pacesetterawards/endpoints`) PaceSetter publica
  **6 servicios**, los que ya usa esta app: INV 1.2.1, Product 1.0.0, PPC 1.0.0, MED 1.1.0, OSN 1.0.0,
  ODRSTAT 1.0.0. El de pedidos (**Purchase Order**, operación `sendPO`) no está.
- Las URLs donde estaría siguiendo el patrón de los otros (`/PurchaseOrder_Service/...svc`,
  `/PO_Service/...svc`, …) devuelven la página genérica de su web (la misma que una ruta inventada),
  no un servicio WCF.
- Sus condiciones (*General Information*):
  - *"Custom orders require an emailed purchase order."* Los cambios, *"in writing via email with a
    revised purchase order"*. La confirmación la mandan por email.
  - Los textos de personalización, **en su Excel** (también aceptan CSV/Word); si no, $2.50 por pieza
    de *typesetting*.
  - Drop ship (envío directo al cliente final), también en su Excel: $8.80 por destino, y $250 de
    recargo si hay más de 10 destinos sin la plantilla.
  - Producción: 9 días hábiles desde la aprobación del arte. Pedidos de menos de $75: $15 de gestión.
- La plantilla está en su zona de distribuidores (con login). La cuenta tiene además
  *"Provisional Orders"*; sin entrar no se sabe si es una forma de pedir online.

Conclusión: lo que se puede hacer más seguro no es el transporte, sino **eliminar la transcripción** y
**dejar registro** de qué se pidió y cuándo.

## 2. Preguntas antes de construir nada (fase 0 — bloqueante)

Al cliente:

1. **¿Quién graba?** ¿PaceSetter entrega la pieza grabada o tu cliente la graba en su taller? Decide
   si el pedido lleva personalización o es pieza en blanco.
2. **¿A dónde se envía?** ¿PaceSetter envía al comprador (drop ship) o al taller de tu cliente?
3. **¿Dónde guarda la tienda el texto a grabar?** *Respondida en parte (2026-09-22, órdenes de la
   tienda dev):* en las propiedades de la línea (`lineItem.customAttributes`), que escribe la app
   **Easify** (la marca con `_tpo_add_by=easify`). Las claves dependen del producto: `Engraving`,
   `Engraving Style` y `Line 1…6` en unos, `text-box-1` en otros. Por eso la vista previa las muestra
   tal cual (sin las que empiezan por `_`) en vez de esperar nombres fijos. En las órdenes migradas las
   instrucciones van en la **nota de la orden** ("Use 16pt Avenir Book for lines 1, 4, 5"), que la
   vista previa también muestra. Falta ver una orden real de un producto de PaceSetter con grabado.
4. **La plantilla Excel de PaceSetter**, la dirección a la que se mandan hoy los pedidos, el número de
   cuenta de distribuidor y un email de pedido real ya enviado (para copiar lo que PaceSetter espera
   ver).
5. **¿Qué número de PO se usa hoy?** Tiene que ser el nombre de la orden de Shopify (ver §4).

A PaceSetter (un email de su comercial):

6. **¿Aceptan pedidos por PromoStandards (`sendPO` 1.0.0)** aunque no lo tengan publicado, o lo
   tienen previsto? Si dicen que sí, es la opción más limpia (§6, fase 4).
7. ¿Qué es *Provisional Orders* en su web? ¿Sirve para meter pedidos?
8. ¿Qué precio quieren en la PO: el de catálogo o el neto de distribuidor?

## 3. Una función, dos entradas

Enviar una orden a PaceSetter es **una sola función** de la app, con dos entradas que llaman a los
mismos endpoints. No son alternativas: se hacen las dos, en este orden (decidido 2026-09-22).

1. **Pestaña Pedidos de la consola** — primero, y se queda. Es la única que responde a "¿qué falta
   por enviar?": lista las órdenes con líneas de PaceSetter que no llevan la etiqueta
   `pacesetter-enviado`. La búsqueda de órdenes de Shopify filtra por etiqueta
   (`-tag:pacesetter-enviado`), no por metafield; las líneas se filtran después por `vendor_sku`.
   Con sólo un botón en Shopify, una orden en la que nadie pulsa se queda olvidada. Además se prueba
   sin desplegar nada en Shopify.
2. **Botón en la orden de Shopify** — después. Llama a lo mismo; su seguridad, en §3.1.

Flujo, en dos clics como la agrupación de productos, se entre por donde se entre:

1. Elegir la orden: en la lista de pendientes de la pestaña, o con **Más acciones → Enviar a
   PaceSetter** en la propia orden.
2. **Vista previa** (no escribe nada):
   - sólo las líneas de PaceSetter, reconocidas por `trophy_sync.vendor_sku` de la variante (el part
     id, que es el número de artículo de PaceSetter), con cantidad y texto a grabar;
   - las líneas de otros proveedores, listadas aparte como excluidas;
   - dirección de envío (la de la orden si es drop ship, la del taller si no);
   - PO = nombre de la orden sin `#`;
   - avisos que **bloquean** el envío: una línea de PaceSetter sin `vendor_sku`, un texto a grabar que
     falta, la orden ya enviada.
3. **Confirmar**: genera la PO y el fichero de personalización/drop ship en el formato de PaceSetter,
   y los envía (fase 3) o los deja para descargar y adjuntar a mano (fase 1).
4. **Marca la orden**: metafield `trophy_sync.pacesetter_po` (JSON: PO, fecha, líneas enviadas, quién)
   y etiqueta `pacesetter-enviado`. Con el metafield, un segundo clic responde 409 y muestra lo que
   se envió (reenviar exige `?resend=true` explícito), entre por la puerta que entre: la marca está
   en la orden, no en la pantalla desde la que se pulsó. También guarda el enlace PO → orden en
   `order_link` (`OrderStore.saveOrder`), así `OrderSyncService` la encuentra por id y no por búsqueda.

Endpoints (`/api/orders`):

- `GET  /pacesetter-pending` — la lista de la pestaña: órdenes con líneas de PaceSetter sin enviar.
- `GET  /{orderId}/pacesetter-po` — la vista previa.
- `POST /{orderId}/pacesetter-po` — confirmar (marca + genera/envía).
- `GET  /{orderId}/pacesetter-po/files` — la PO y el fichero de personalización ya generados.

Configuración nueva (`orders.pacesetter.*` en `application.yaml`, secretos por env): email de
destino, CC, número de cuenta, dirección del taller, método de envío por defecto, y `enabled`
(false por defecto, como la creación de productos).

### 3.1 Seguridad del botón: "sólo desde mi tienda"

No hace falta un mecanismo nuevo. El botón es una extensión **de esta misma app**, y Shopify firma
cada llamada suya con un session token: un JWT HS256 firmado con el secreto de la app, con `aud` = el
client id y `dest` = la tienda. `ShopifySessionToken` ya lo verifica para la consola embebida. Hay dos
formas de montar el botón:

- **Admin link** (la elegida): "Más acciones → Enviar a PaceSetter" abre la app embebida en la orden,
  con la vista previa. Es la consola de siempre con su autenticación de siempre: cero trabajo de
  seguridad.
- **Admin action** (un modal dentro de la página de la orden, sólo si se quiere no salir de ella):
  llama a los endpoints con `fetch()`, y Shopify añade solo `Authorization: Bearer <token>` cuando la
  llamada va al dominio de la app (comprobado en su documentación, 2026-09-22). Pide dos cosas que hoy
  no existen: CORS para el origen `https://extensions.shopifycdn.com`, y dejar pasar sin sesión la
  petición `OPTIONS` previa. El navegador la manda sin token, y hoy `SessionAuthFilter` protege todo
  `/api/**` sin mirar el método, así que la rechazaría con 401.

Descartado:

- **Un endpoint público con una clave compartida** (por ejemplo, una petición HTTP desde Shopify
  Flow): la clave queda guardada en la configuración de Flow, y quien la lea puede enviar pedidos.
- **Un webhook `orders/create` que envíe solo**: la firma HMAC es segura, pero nadie revisa el texto
  a grabar antes de que salga, y Shopify reintenta los webhooks, lo que puede duplicar un envío. Un
  webhook sólo serviría para avisar de que hay una orden pendiente, nunca para enviarla.

## 4. Qué hay que arreglar del sync de órdenes que ya existe

Sin esto, el pedido sale pero el estado y el tracking no vuelven bien. Visto leyendo
`OrderSyncService`; nada de esto se ha probado en vivo.

1. **Scopes.** La app tiene `write_assigned_fulfillment_orders`, que sólo cubre las fulfillment orders
   de un servicio de fulfillment propio de la app. Las de una location normal de la tienda son
   *merchant managed* y piden `write_merchant_managed_fulfillment_orders`. Escribir un metafield o
   una etiqueta en una orden pide `write_orders`. **La app de producción no tiene ninguno de los
   dos** (comprobado 2026-09-21), así que el sync actual casi seguro sería rechazado en la tienda
   real: hay que pedirlos y reautorizar. La app dev nueva, en la tienda `trophy-partner-dev`, ya los
   tiene, así que ahí se puede probar todo antes.
2. **Cumple la orden entera.** `fulfillmentCreate` va sin `fulfillmentOrderLineItems`, así que marca
   enviadas también las líneas que no son de PaceSetter, con el tracking de PaceSetter. Debe cumplir
   sólo las líneas de PaceSetter (las mismas que la PO).
3. **Varios paquetes.** El primero cierra la fulfillment order; el segundo falla, la pasada entera se
   cancela y ese tracking no llega nunca. El segundo paquete debe añadirse al fulfillment existente
   (`fulfillmentTrackingInfoUpdate`) o cumplir sólo sus líneas. No hay test con más de un paquete.
4. **El estado sólo se escribe al enviar.** `custom.supplier_status` se escribe dentro del bucle de
   envíos, así que un pedido "In Production" sin enviar no muestra nada. Recorrer también los PO de
   `latestStatusByPo`.
5. **Unir por el PO guardado**, no adivinando con `name:<po>` (ya lo hace si hay `order_link`; necesita
   `sync.persistence.enabled`).

## 5. Formato de lo que se envía

- **PO**: número, fecha, cuenta de distribuidor, y por línea artículo (part id), descripción,
  cantidad, precio; dirección y método de envío; fecha requerida si la orden la tiene. Sale de la
  orden de Shopify + el catálogo que ya leemos, nunca tecleado.
  *Hecho (2026-09-23)*: la plantilla es **el correo que el cliente ya enviaba** (lo pasó él mismo):
  saludo, "I'd like to place an order for the following items", tabla de artículos, prueba de
  producción para aprobar, transporte y fecha de llegada, dirección, número de PO y firma. El asunto
  es el suyo: `TrophyPartner.com Order P.O. # <nº>`. La tabla lleva artículo, descripción, cantidad y
  texto a grabar, **sin precios**: PaceSetter factura con su propia tarifa. Dos datos nuevos de
  configuración que pedía ese texto: `contact` (a quién saluda) y `ship-account` (la cuenta de UPS
  propia; vacía, la frase desaparece). La **fecha requerida** sale de una propiedad de la orden o de
  la línea que mencione una fecha, o del `DATE NEEDED:` de la nota, que es donde está en todas las
  órdenes migradas; si nadie la dijo, el correo pide "as soon as possible".
  *Hecho (2026-09-22)*: el cuerpo es el **email**, y vive en un fichero HTML con marcas `{{...}}`
  (`email/pacesetter-po.html`, o el que diga `orders.pacesetter.template`), que se lee en cada
  render: se edita sin recompilar. El destinatario, la copia, el remitente, el asunto, la cuenta y la
  firma son configuración (`orders.pacesetter.*`, secretos por env). Todo lo que se sustituye va
  escapado, porque el texto a grabar lo escribe el comprador. Falta el formato definitivo, que
  depende de un pedido real de PaceSetter (§2.4).
- **Personalización / drop ship**: en el formato exacto de la plantilla de PaceSetter (columnas por
  confirmar con la plantilla, §2.4). Si aceptan CSV, CSV; si no, `.xlsx`.
- Sin dependencias nuevas en fases 1–2: el CSV es texto y un `.xlsx` sencillo es un zip de XML
  (`java.util.zip`). La PO puede ir como cuerpo del email en HTML, sin PDF.

## 6. Fases

0. **Preguntas** (§2). Sin respuesta a 1–4 no se puede fijar el formato.
1. **Pestaña Pedidos + vista previa + ficheros** — la lista de pendientes, los endpoints y la marca
   en la orden. El envío sigue siendo del buzón del cliente, adjuntando lo generado. Cero
   dependencias nuevas. Se prueba entero en la tienda dev, que ya tiene los scopes.
   Tests con el `ShopifyHttp` falso: filtro de líneas, texto a grabar, orden ya enviada (409), línea
   sin `vendor_sku` (bloquea), orden mixta, y que una orden marcada deje de salir en pendientes.
2. **Botón en la orden** — extensión *admin link* (Shopify CLI) hacia la pantalla de la fase 1. La
   *admin action* sólo si hace falta no salir de la orden, con el CORS y el `OPTIONS` de §3.1.
3. **Envío por email desde la app** — *hecho el 2026-09-22*: `spring-boot-starter-mail` (primera
   dependencia nueva del proyecto), `spring.mail.*` para el servidor y `orders.pacesetter.*` para el
   mensaje; copia al cliente, siempre. Primero el email y después las marcas (metafield con lo que se
   envió, y etiqueta con `tagsAdd`, que no pisa las demás): una marca que falla se reporta en
   `markError` y nunca se lanza, porque reintentar enviaría la PO otra vez. Un segundo envío exige
   `?resend=true`. El destinatario, la copia y la copia oculta se pueden cambiar **para un envío
   concreto** (cuerpo del POST); la consola los precarga con los de configuración y avisa en cuanto
   uno no es el de la app, y el registro guarda a dónde se envió de verdad. **Falta**: el buzón real y
   sus credenciales, encender `enabled`, y el scope `write_orders` en la app de producción para poder
   marcar. El botón de la consola está **desactivado a propósito** hasta cerrar el formato con
   PaceSetter.
4. **`sendPO` por SOAP**, sólo si PaceSetter lo ofrece (§2.6): mismo patrón que los otros 6 servicios
   (WSDL vendorizado, perfil de codegen, stub + cliente SOAP). La especificación 1.0.0 cubre lo que
   hace falta: `orderType` `Configured`, `ShipmentArray`/`ShipTo`, `LineItemArray`, y el texto a
   grabar por línea en `Configuration › LocationArray › DecorationArray › Artwork › TypesetArray`.
   Devuelve un `transactionId`. Sólo cambiaría el paso 3 del botón.

En paralelo a la fase 1: los arreglos del §4 (scopes primero).

## 7. Riesgos

- **Enviar dos veces**: metafield + confirmación en dos pasos; reenviar es explícito. El 409 vale
  para las dos entradas.
- **Una orden que nadie envía**: la lista de pendientes de la pestaña, que no depende de que alguien
  pulse el botón.
- **Pedir el artículo equivocado**: una línea sin `vendor_sku` bloquea, no se omite en silencio.
- **Cambios después de enviar**: PaceSetter exige una PO revisada por email. La app no los gestiona;
  muestra lo que se envió y cuándo.
- **Datos personales**: la dirección del comprador va en el email, como hoy.

## Fuentes

- Registro PromoStandards: `https://services.promostandards.org/WebServiceRepository/WebServiceRepository.svc/json/companies/pacesetterawards/endpoints`
- PaceSetter, condiciones: https://m.pacesetterawards.com/info/general_information
- PaceSetter, plantilla de personalización y drop ship: https://m.pacesetterawards.com/info/personalization_and_drop_ship_form
- Purchase Order 1.0.0 (estructura de `sendPO`): https://docs.psrestful.com/standards/purchase-order-1.0.0
- Scopes de `fulfillmentCreate`: https://shopify.dev/docs/api/admin-graphql/latest/mutations/fulfillmentCreate
- Admin links: https://shopify.dev/docs/apps/build/admin/admin-links
- Admin actions → backend de la app (token automático, CORS): https://shopify.dev/docs/apps/build/admin/actions-blocks/connect-app-backend
- Extensiones de admin, red: https://shopify.dev/docs/api/admin-extensions/latest/network-features
