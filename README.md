# PromoStandards Integration Service

A Spring Boot (3.5.x, Java 17) service that exposes a REST API in front of the
[PromoStandards](https://promostandards.org) supplier web services. Each PromoStandards service is
fronted by a Java **client interface** with a swappable implementation. The bundled implementations
are **in-memory stubs** so the full REST API is exercisable today; real SOAP-backed clients can be
dropped in later **without touching the service or web layers**.

## Services covered

| PromoStandards service        | Version | REST base path         | Client interface       |
|-------------------------------|---------|------------------------|------------------------|
| Inventory                     | 1.2.1   | `/api/inventory`       | `InventoryClient`      |
| Product Data                  | 1.0.0   | `/api/products`        | `ProductDataClient`    |
| Pricing and Configuration     | 1.0.0   | `/api/pricing`         | `PricingClient`        |
| Media Content                 | 1.1.0   | `/api/media`           | `MediaClient`          |
| Order Shipment Notification   | 1.0.0   | `/api/order-shipments` | `OrderShipmentClient`  |
| Order Status                  | 1.0.0   | `/api/order-status`    | `OrderStatusClient`    |

## Architecture

Each service is a self-contained vertical slice under `com.trophy.promostandards.<service>`:

```
<service>/
  model/    immutable request/response records mirroring the PromoStandards types
  client/   XxxClient interface  +  StubXxxClient (@ConditionalOnProperty mode=stub)
  service/  XxxService — applies configured credentials/wsVersion, delegates to the client
  web/      XxxController — REST facade
```

Shared pieces live in `common/` (`ServiceMessage`, `PromoStandardsClientException`,
`GlobalExceptionHandler`) and `config/` (`PromoStandardsProperties`).

Request flow: `Controller → Service → Client (stub | SOAP)`. Controllers never see credentials —
the service layer reads them from configuration and stamps every request with the account
`id`/`password` and the service `wsVersion`, exactly as a SOAP request requires.

### Error handling

Clients throw `PromoStandardsClientException`, carrying any PromoStandards `ServiceMessage`s. A real
SOAP client maps SOAP faults and `ServiceMessageArray` entries onto the same exception, so callers
handle stub and live failures identically. `GlobalExceptionHandler` renders it as HTTP `502 Bad
Gateway` (an upstream/supplier failure); invalid input renders as `400`.

## Running

```bash
./mvnw spring-boot:run
```

The API starts on `http://localhost:8080` using the stub clients. Examples:

```bash
curl "http://localhost:8080/api/inventory/SAMPLE-001/levels"
curl "http://localhost:8080/api/inventory/SAMPLE-001/levels?partColor=Blue&labelSize=S"
curl "http://localhost:8080/api/products/SAMPLE-001"
curl "http://localhost:8080/api/products/SAMPLE-001/sellable"
curl "http://localhost:8080/api/products/date-modified?changedSince=2026-01-01T00:00:00Z"
curl "http://localhost:8080/api/pricing/SAMPLE-001/configuration?currency=USD"
curl "http://localhost:8080/api/pricing/SAMPLE-001/charges"
curl "http://localhost:8080/api/pricing/SAMPLE-001/fob-points"
curl "http://localhost:8080/api/media/SAMPLE-001"
curl "http://localhost:8080/api/media/SAMPLE-001?mediaType=Image"
```

## Configuration

All settings live under the `promostandards.*` namespace (see `application.yaml`):

```yaml
promostandards:
  credentials:
    id: YOUR_ACCOUNT_ID
    password: YOUR_PASSWORD
  inventory:
    mode: stub                # stub (default) | soap
    ws-version: 2.0.0
    endpoint-url: ""          # SOAP endpoint (used once mode=soap)
  # ...likewise for product-data, pricing, media
```

## Replacing a stub with a real SOAP client

The point of the design: swap one service at a time, with no changes to `service/` or `web/`.

1. Add the SOAP stack to `pom.xml` (e.g. `spring-boot-starter-web-services` / JAX-WS) and generate
   client bindings from the supplier's WSDL.
2. Implement the client interface, e.g.:

   ```java
   @Component
   @ConditionalOnProperty(prefix = "promostandards.inventory", name = "mode", havingValue = "soap")
   public class SoapInventoryClient implements InventoryClient {
       // build the SOAP request from the model record, call the endpoint,
       // map the response back to the model records,
       // and map faults/ServiceMessageArray to PromoStandardsClientException.
   }
   ```

   It activates on `mode=soap` while `StubInventoryClient` activates on `mode=stub`
   (`matchIfMissing = true`), so exactly one bean is wired per service.
3. Set `promostandards.inventory.mode=soap`, fill in `endpoint-url` and credentials, and restart.

Repeat per service. The model records may need extra fields as you map richer WSDL types — they are
deliberately a focused subset today.

## First real integration: Inventory via Apache CXF

Concrete runbook for the first live supplier (Inventory 2.0.0, Apache CXF 4.x). Steps marked
**(needs input)** require values from the supplier.

1. **(needs input)** Obtain the supplier's **WSDL** (usually `<endpoint-url>?wsdl` or `?singleWsdl`),
   **endpoint URL**, and account **id/password**.
2. Vendor the WSDL + XSDs under `src/main/resources/wsdl/inventory/` so the build is reproducible
   (no network at build time).
3. Add Apache CXF + the codegen plugin to `pom.xml`; generate JAX-WS client stubs from the WSDL
   into `target/generated-sources`.
4. Implement `SoapInventoryClient implements InventoryClient`, annotated
   `@ConditionalOnProperty(prefix = "promostandards.inventory", name = "mode", havingValue = "soap")`.
   Map `GetInventoryLevelsRequest`/`GetFilterValuesRequest` onto the generated request types
   (stamping id/password/wsVersion from config), and map the response — plus any
   `ServiceMessageArray`/SOAP fault — back to the model records / `PromoStandardsClientException`.
5. Configure the endpoint + a CXF logging interceptor and connect/receive timeouts.
6. Supply credentials safely (see below), set `promostandards.inventory.mode=soap`, and smoke-test
   `GET /api/inventory/{productId}/levels` against a real product id.

### Credentials & secrets

Real credentials are **never committed**. Provide them either way:

- **Environment variables** (relaxed-binding): `PROMOSTANDARDS_CREDENTIALS_ID`,
  `PROMOSTANDARDS_CREDENTIALS_PASSWORD`, `PROMOSTANDARDS_INVENTORY_ENDPOINT`.
- **Local file**: copy `application-local.yaml.example` → `application-local.yaml`
  (git-ignored) and run with `-Dspring-boot.run.profiles=local`.

> Note: credentials are currently modeled globally (`promostandards.credentials.*`), which fits a
> single supplier. Integrating multiple suppliers later will mean moving credentials to a
> per-supplier (or per-service) structure.

## Testing

```bash
./mvnw test
```

`PromoStandardsApiTest` is a `@SpringBootTest` + MockMvc test hitting one primary endpoint per
service against the stubs; `StubInventoryClientTest` covers stub filtering and the required-field
error path.
