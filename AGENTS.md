# PromoStandards Integration Service

Spring Boot **3.5.15 / Java 17** service that exposes a **REST API** in front of the
[PromoStandards](https://promostandards.org) supplier SOAP web services. Each PromoStandards
service is fronted by a Java **client interface** with two swappable implementations selected by
config: an in-memory **stub** (default) and a real **SOAP** client (Apache CXF / JAX-WS).
Group: `com.trophy.promostandards`. Primary target supplier: **PaceSetter**.

## Services (6) — actual versions in `application.yaml`

| Service                         | ws-version | REST base path        | Client interface       |
|---------------------------------|-----------|------------------------|------------------------|
| Inventory                       | 1.2.1     | `/api/inventory`       | `InventoryClient`      |
| Product Data                    | 1.0.0     | `/api/products`        | `ProductDataClient`    |
| Pricing & Configuration (PPC)   | 1.0.0     | `/api/pricing`         | `PricingClient`        |
| Media Content                   | 1.1.0     | `/api/media`           | `MediaClient`          |
| Order Shipment Notification     | 1.0.0     | `/api/order-shipments` | `OrderShipmentClient`  |
| Order Status                    | 1.0.0     | `/api/order-status`    | `OrderStatusClient`    |

> Note: `README.md`'s header table is stale (lists 2.0.0 for Inventory/Product). The versions
> above (from `application.yaml`) are authoritative.

## Architecture

Each service is a self-contained vertical slice under `com.trophy.promostandards.<service>`:

```
<service>/
  model/    immutable request/response records mirroring the PromoStandards types
  client/   XxxClient interface
            StubXxxClient   (@ConditionalOnProperty mode=stub, matchIfMissing=true)
            SoapXxxClient   (@ConditionalOnProperty mode=soap) — maps model <-> CXF stubs
            XxxSoapConfig   (builds the JAX-WS port, timeouts, optional logging)
  service/  XxxService — stamps credentials/wsVersion, delegates to the client
  web/      XxxController — REST facade (never sees credentials)
```

Shared: `common/` (`ServiceMessage`, `PromoStandardsClientException`, `web/GlobalExceptionHandler`,
`web/ErrorResponse`), `config/PromoStandardsProperties`.

Request flow: `Controller → Service → Client (stub | soap)`. Exactly one client bean is wired per
service via `mode`. Clients throw `PromoStandardsClientException` (carrying any `ServiceMessage`s);
`GlobalExceptionHandler` maps it to HTTP **502** (upstream/supplier failure) and invalid input to
**400**.

## Build & run

**Use the locally installed `mvn`, NOT `./mvnw`** — the sandbox blocks the wrapper's download.

```bash
mvn test                 # full suite (stub + SOAP-mapping tests)
mvn spring-boot:run      # starts on http://localhost:8080 (stub mode by default)
```

- The static console at `http://localhost:8080/` (`src/main/resources/static/`: `index.html`,
  `app.css`, `app.js`, `polaris-tokens.css`) is a **Shopify-admin-style (Polaris) products table**:
  one row per product (thumbnail, variants, inventory + status badge, price range, sync status),
  a collapsible row revealing the variant inventory grid, and per-row **Add to Shopify / Sync**
  buttons wired to `/api/sync/*`. Plain HTML/CSS/JS, no framework; Polaris look is hand-built on the
  vendored token subset in `polaris-tokens.css` (the real `@shopify/polaris` styles.css needs the
  React components, so only its `:root` tokens are used). **Perf-critical design:** the table never
  aggregates the whole catalog. `GET /api/catalog/products` is a *cheap* list (`{productId,
  imported}` only — one upstream `getProductSellable` call); each row's title/inventory/price/
  thumbnail is fetched **lazily for the visible page only** via `GET /api/catalog/products/{id}`
  (the expensive Product+Pricing+Inventory+Media aggregation), with a JS concurrency cap (5) and
  client-side pagination. Aggregating all products up front floods the supplier and takes minutes —
  don't reintroduce that. The page auto-loads (no manual button).

  Four more invariants hold the read path's cost down — each one was a measured regression:
  1. **The `imported` flag never comes from a fresh Shopify listing per row.** `detail()` reads
     `ShopifySyncService.isImported()` (the cached supplier-id index, 5-min TTL, one rebuild per
     burst); the catalog list is the only place that pages the store. Paging it per row made a
     25-row table page cost 25 full paginations of every tagged product.
  2. **Both reads are TTL-cached** (`sync.catalog.*`, `TtlCache`: per-key single-flight, failures
     not cached) — detail 15m, sellable-id list 1h. Hand-rolled because the build takes no new deps
     and `ConcurrentMapCacheManager` has no expiry.
  3. **`detail()` fans out to the 5 services in parallel** on a bounded pool (`fetch-threads`, also
     the ceiling on concurrent supplier calls) and is **independently fault-tolerant per service** —
     a service with no record costs a `warnings` entry, not the row. Only an id that *every* service
     comes up empty on is a 404.
  4. **Front-end enrichment is generation-scoped and the search box is debounced** (`app.js`): each
     render cancels the previous render's queued row fetches, so keystrokes can't bury the visible
     rows behind an obsolete backlog.
  5. **Catalog-wide Product Data goes through `SupplierProductScan`** — one throttled `getProduct`
     pass, memoised 10 min with single-flight. Both catalog-wide indexes consume it (title index for
     name search, group index for families), so the console load triggers one pass, not one each.
     Any future catalog-wide feature must use it rather than adding a pass.
- Config is **YAML** (`application.yaml`), not `.properties`.

### SOAP codegen (CXF `wsdl2java`)

WSDLs + XSDs are vendored under `src/main/resources/wsdl/<service>/` so builds are reproducible
(no network at build time). `pom.xml` has one Maven profile per service that **auto-activates when
its WSDL file exists** and generates JAX-WS client stubs into `target/generated-sources/cxf`
(packages `com.trophy.promostandards.<service>.soap[.shared|.xsstl|.iso4217]`). All WSDLs are
currently present, so all 6 codegen profiles fire on every build.

## Configuration

All settings under the `promostandards.*` namespace. Per service: `mode` (`stub`|`soap`),
`ws-version`, `endpoint-url`, `connect-timeout-ms`, `receive-timeout-ms`, `log-messages`.

**Credentials are never committed.** Provide via:
- Env vars (relaxed binding): `PROMOSTANDARDS_CREDENTIALS_ID`, `PROMOSTANDARDS_CREDENTIALS_PASSWORD`,
  and per-service endpoints like `PROMOSTANDARDS_INVENTORY_ENDPOINT`,
  `PROMOSTANDARDS_PRODUCT_ENDPOINT`, `PROMOSTANDARDS_PRICING_ENDPOINT`,
  `PROMOSTANDARDS_MEDIA_ENDPOINT`, `PROMOSTANDARDS_ORDER_SHIPMENT_ENDPOINT`,
  `PROMOSTANDARDS_ORDER_STATUS_ENDPOINT`.
- Local file: copy `application-local.yaml.example` → `application-local.yaml` (git-ignored), run
  with `-Dspring-boot.run.profiles=local`.

Credentials are modeled **globally** (`promostandards.credentials.*`) — fits one supplier; supporting
multiple suppliers later means moving them to a per-supplier structure.

## REST endpoints

- `GET /api/inventory/{productId}/levels` (`?partColor=&labelSize=`), `…/filter-values`
- `GET /api/products/{productId}`, `/api/products/sellable`, `/close-out`, `/date-modified?changedSince=`
- `GET /api/pricing/{productId}/configuration?currency=` (price-break matrix **+ `locations`**: imprint
  locations with each decoration method's area geometry/height/width/diameter/uom — the structured
  form of the supplier's PDF spec sheets), `…/charges`, `…/fob-points`
- `GET /api/media/{productId}` (`?mediaType=`), `/api/media/date-modified`
- `GET /api/order-shipments?poNumber=` **or** `?since=` (one required)
- `GET /api/order-status?poNumber=` **or** `?since=`; `GET /api/order-status/types`

## Shopify sync (PromoSync-style)

Two packages turn supplier data into synced Shopify products (the PromoSync goal):

- `shopify/` — generic Shopify Admin GraphQL client (ported from the `trophypartner` app). Uses the
  JDK `java.net.http.HttpClient` behind the `ShopifyHttp` interface (**no WebClient/webflux** — see
  gotcha). `ShopifyTokenService` does the client_credentials OAuth grant + token caching;
  `ShopifyGraphQLClient.execute(query, vars)` returns the `data` node and throws on top-level errors.
  Config under `shopify.*` (creds, `api-version`, `location-id`); secrets via env.
- `sync/` — orchestration. `CatalogService.aggregate(productId)` joins the 6 services into a
  `SupplierProduct` (variants = part × size; **inventory joins on (color,size)**, price/media on the
  color-level partId). PaceSetter sends literal `"N/A"`/`"NULL"` placeholders (part color, brand):
  `norm()` treats those as null, and a Product-Data-only variant subsumed by an inventory row with
  the same partId is dropped (else e.g. C1925 imports a phantom zero-stock "N/A / One Size" variant
  next to the real "Clear / 5.75 X 5" — see `CatalogServicePlaceholderTest`). `ShopifyProductMapper` builds `productSet` variables (deterministic handle
  `ps-<supplier>-<id>` for idempotent upsert, Color/Size options, `custom.ps_*` identity metafields,
  plus per-**variant** `custom.promo_standard_id` = supplier productId). Every import also stamps the
  `custom.promo_standard_supplier` product metafield (`metaobject_reference`) pointing at the store
  metaobject configured in `sync.supplier-metaobject` (type/handle, default
  `promo_standard_supplier`/`pace-setter`); its GID is resolved once via `metaobjectByHandle`
  (**needs the `read_metaobjects` scope**; if the entry is missing, the import proceeds with a WARN
  and without that metafield).
  `PricingPolicy` applies markup/MAP-floor/rounding. `ShopifySyncService` does import + inventory
  (`inventorySetQuantities`) + price (`productVariantsBulkUpdate`); `OrderSyncService` pushes status +
  tracking (`fulfillmentCreate`). `SyncScheduler` runs cron jobs gated on `sync.schedule.enabled`.

REST (`/api/sync`): `POST /products/{id}`, `POST /products` (batch), `POST /products/{id}/inventory`,
`POST /products/{id}/pricing`, `POST /orders?since=`. Config lives under `sync.*` in application.yaml.
Catalog reads (`/api/catalog`): `GET /products`, `GET /products/{id}`, `GET /product-titles`
(name/vendor per id — backs the console's local name search), `GET /product-groups`, plus
`POST /product-{titles,groups}/refresh` to force a background rebuild of either index.

GraphQL strings are in `sync/ShopifyGraphQL.java` — validate shapes against the target API version
with the `shopify-dev-mcp` tools before going live. Shopify rate-limits by query **cost** and
reports exhaustion as HTTP 200 + `errors[].extensions.code=THROTTLED`; `ShopifyGraphQLClient` retries
those (waiting the bucket's own restore time when reported), configured under `shopify.retry.*`. **Open item:** `OrderSyncService` matches a
supplier PO to a Shopify order via `name:<po>` — adjust to however POs are actually recorded.

## Deployment (Contabo / Docker)

`Dockerfile` (two-stage: Maven temurin-17 build → `17-jre-alpine`, non-root, healthcheck on
`/actuator/health`), `docker-compose.yaml` (binds **127.0.0.1:8080 only** — the app has no auth;
front with nginx basic-auth or an SSH tunnel), `.env.example` (server credentials template),
`DEPLOY.md` (full runbook: scp source → build on server, TLS-fallback cert import, nginx+certbot).
The `prod` Spring profile (`application-prod.yaml`, set via compose) switches all 6 services to
`mode=soap` with the PaceSetter endpoints; `SYNC_SCHEDULE_ENABLED` gates the cron jobs (default
false). **`.dockerignore` must keep excluding `application-local.yaml`** — it holds real
credentials and would otherwise be baked into the image. No Docker on this dev machine — images
build/run on the server only.

## Tests

`@SpringBootTest` + MockMvc in `PromoStandardsApiTest` (one endpoint per service vs stubs).
Per-service `Soap*ClientTest` verify model↔SOAP-stub mapping; `StubInventoryClientTest` covers stub
filtering + the required-field error path. Sync tests: `CatalogServiceTest` (join vs stubs),
`PricingPolicyTest`/`ShopifyProductMapperTest` (pure), `ShopifySyncServiceTest`/`OrderSyncServiceTest`
(fake `ShopifyHttp` routing by GraphQL op), `SyncApiTest` (web layer + 502 mapping), plus the ported
`Shopify*Test`. **54 tests green** at last run.

## Gotchas / known issues

- **PKIX/TLS failures on this machine are truststore-related, not network**: the Oracle JDK 21
  cacerts doesn't trust some chains the Windows cert store does. Fix by pointing the JVM at the
  Windows store: `-Djavax.net.ssl.trustStoreType=Windows-ROOT`. Verified for Maven Central
  (`$env:MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"` before `mvn`); likely also fixes
  the live PaceSetter SOAP 502s locally (set it in `JAVA_TOOL_OPTIONS` when running the app). In the
  Linux container this doesn't apply — the Dockerfile has a commented keytool cert-import fallback.
- **The Bash tool's sandbox blocks Maven Central**; run `mvn` from the PowerShell tool (with the
  `MAVEN_OPTS` above) when uncached deps must be fetched. The Shopify client deliberately uses the JDK `HttpClient`
  (via the `ShopifyHttp` interface) instead of `spring-boot-starter-webflux`, so it needs **zero new
  deps** and builds offline (`mvn -o`) in either tool.
- Live Shopify calls can't be exercised here (no creds, Central/TLS blocked). Sync is verified by the
  full `@SpringBootTest` context loading + unit tests with a fake `ShopifyHttp`; live smoke-test needs
  a real dev store via env vars.
- Not a git repo. `scripts/get-pacesetter-product-ids.ps1` pulls sample product IDs.
  `src/main/resources/supliers.txt` (sic) is scratch data of supplier codes from the PromoStandards
  endpoint repository.
