# Pedidos a PaceSetter desde Shopify

Estado: **plan** (2026-09-18). Nada implementado todavía.

Objetivo: en una orden de Shopify, un botón que prepare el pedido a PaceSetter con los datos de la
orden — sin que nadie los copie a mano — y deje la orden unida al PO, para que el estado y el tracking
que ya leemos de PaceSetter vuelvan solos a esa orden.

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
3. **¿Dónde guarda la tienda el texto a grabar?** Hace falta una orden real con grabado; lo más
   probable es que esté en las propiedades de la línea (`lineItem.customAttributes`).
4. **La plantilla Excel de PaceSetter**, la dirección a la que se mandan hoy los pedidos, el número de
   cuenta de distribuidor y un email de pedido real ya enviado (para copiar lo que PaceSetter espera
   ver).
5. **¿Qué número de PO se usa hoy?** Tiene que ser el nombre de la orden de Shopify (ver §4).

A PaceSetter (un email de su comercial):

6. **¿Aceptan pedidos por PromoStandards (`sendPO` 1.0.0)** aunque no lo tengan publicado, o lo
   tienen previsto? Si dicen que sí, es la opción más limpia (§6, fase 4).
7. ¿Qué es *Provisional Orders* en su web? ¿Sirve para meter pedidos?
8. ¿Qué precio quieren en la PO: el de catálogo o el neto de distribuidor?

## 3. El botón

Flujo, en dos clics como la agrupación de productos:

1. En la orden, **Más acciones → Enviar a PaceSetter** (extensión *admin link*: abre la app embebida
   con el id de la orden). Mientras no exista la extensión, lo mismo desde una pestaña **Pedidos** de
   la consola, que lista las órdenes recientes con líneas de PaceSetter.
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
   se envió (reenviar exige `?resend=true` explícito). También guarda el enlace PO → orden en
   `order_link` (`OrderStore.saveOrder`), así `OrderSyncService` la encuentra por id y no por búsqueda.

Endpoints (`/api/orders`):

- `GET  /{orderId}/pacesetter-po` — la vista previa.
- `POST /{orderId}/pacesetter-po` — confirmar (marca + genera/envía).
- `GET  /{orderId}/pacesetter-po/files` — la PO y el fichero de personalización ya generados.

Configuración nueva (`orders.pacesetter.*` en `application.yaml`, secretos por env): email de
destino, CC, número de cuenta, dirección del taller, método de envío por defecto, y `enabled`
(false por defecto, como la creación de productos).

## 4. Qué hay que arreglar del sync de órdenes que ya existe

Sin esto, el pedido sale pero el estado y el tracking no vuelven bien. Visto leyendo
`OrderSyncService`; nada de esto se ha probado en vivo.

1. **Scopes.** La app tiene `write_assigned_fulfillment_orders`, que sólo cubre las fulfillment orders
   de un servicio de fulfillment propio de la app. Las de una location normal de la tienda son
   *merchant managed* y piden `write_merchant_managed_fulfillment_orders`. Escribir un metafield o
   una etiqueta en una orden pide `write_orders`. **Hoy no está ninguno de los dos**, así que el sync
   actual casi seguro sería rechazado en la tienda real. Hay que pedirlos y reautorizar.
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
- **Personalización / drop ship**: en el formato exacto de la plantilla de PaceSetter (columnas por
  confirmar con la plantilla, §2.4). Si aceptan CSV, CSV; si no, `.xlsx`.
- Sin dependencias nuevas en fases 1–2: el CSV es texto y un `.xlsx` sencillo es un zip de XML
  (`java.util.zip`). La PO puede ir como cuerpo del email en HTML, sin PDF.

## 6. Fases

0. **Preguntas** (§2). Sin respuesta a 1–4 no se puede fijar el formato.
1. **Vista previa + ficheros** — endpoints, pestaña Pedidos, marca en la orden. El envío sigue
   siendo del buzón del cliente, adjuntando lo generado. Cero dependencias nuevas.
   Tests con el `ShopifyHttp` falso: filtro de líneas, texto a grabar, orden ya enviada (409), línea
   sin `vendor_sku` (bloquea), orden mixta.
2. **Botón en la orden** — extensión *admin link* (Shopify CLI) hacia la pantalla de la fase 1.
3. **Envío por email desde la app** — `spring-boot-starter-mail` (primera dependencia nueva, a
   decidir) + SMTP del buzón por env. Copia al cliente, siempre.
4. **`sendPO` por SOAP**, sólo si PaceSetter lo ofrece (§2.6): mismo patrón que los otros 6 servicios
   (WSDL vendorizado, perfil de codegen, stub + cliente SOAP). La especificación 1.0.0 cubre lo que
   hace falta: `orderType` `Configured`, `ShipmentArray`/`ShipTo`, `LineItemArray`, y el texto a
   grabar por línea en `Configuration › LocationArray › DecorationArray › Artwork › TypesetArray`.
   Devuelve un `transactionId`. Sólo cambiaría el paso 3 del botón.

En paralelo a la fase 1: los arreglos del §4 (scopes primero).

## 7. Riesgos

- **Enviar dos veces**: metafield + confirmación en dos pasos; reenviar es explícito.
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
