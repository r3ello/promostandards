# Abrir esta app dentro del admin de Shopify

Estado: **implementado** (2026-09-08). Falta la configuración en el Partner Dashboard y el
despliegue, que son los dos pasos del servidor.

## Qué fallaba

La app se veía en el admin pero el login no pasaba nunca. La causa es concreta y no tiene que ver
con las credenciales:

Dentro del admin, la app corre **en un iframe de `admin.shopify.com`**. La cookie de sesión de la app
(`PS_SESSION`, `SameSite=Lax`) es entonces una **cookie de terceros**: el navegador la acepta al
hacer login pero **no la devuelve** en las llamadas siguientes. El `POST /api/auth/login` respondía
200 y todo lo demás 401. Poner `SameSite=None; Secure` lo arreglaría hoy en Chrome y seguiría
fallando en Safari, así que no es el camino.

Shopify tiene su propio mecanismo para esto — el **session token** — y es el que se ha implementado.

## Cómo funciona ahora

1. El servidor sirve la consola inyectando **App Bridge** y el `client_id` en el `<head>`
   (`ConsoleIndexController` rellena el marcador `<!--SHOPIFY_APP_BRIDGE-->` de `index.html`).
2. El navegador pide a App Bridge un **JWT firmado HS256 con el client secret de la app**
   (`window.shopify.idToken()`), y `app.js` lo manda como `Authorization: Bearer …` en **cada**
   petición. Los tokens duran ~1 minuto, por eso se pide uno por llamada en vez de cachearlo.
3. El servidor lo verifica (`ShopifySessionToken`): firma, `alg` HS256 de nuestra lista blanca —
   nunca el que diga el token —, `aud` = nuestro client id, `dest` = **nuestra tienda**, y `exp`/`nbf`.
4. `RequestAuthenticator` acepta **cualquiera de las dos credenciales**: la cookie del login de
   siempre (acceso directo por tu dominio) o el token de Shopify (dentro del admin). Nada del acceso
   directo cambia.
5. Cada respuesta declara `Content-Security-Policy: frame-ancestors https://<tienda>
   https://admin.shopify.com` (`EmbeddedFrameFilter`), que es lo que permite al admin enmarcarnos.

Efecto colateral bueno: dentro del admin **sólo entra quien tenga acceso de admin a esa tienda**, y
el usuario/contraseña compartido deja de circular.

## Lo que tienes que hacer tú

### 1. Partner Dashboard → tu app → Configuration

| Campo | Valor |
|---|---|
| **App URL** | `https://TU-DOMINIO/` (la raíz; es lo que el admin mete en el iframe) |
| **Embed app in Shopify admin** | **ON** |
| **Allowed redirection URL(s)** | `https://TU-DOMINIO/` |

Tiene que ser **HTTPS**: el admin no enmarca http.

Ojo con una cosa: esto vale para la app del **Partner Dashboard** (la que tiene App URL y de la
que salen el client id/secret que ya usas). Una "custom app" creada dentro de la tienda
(Settings → Apps → Develop apps) no tiene App URL y **no se puede embeber** — si la que ves en el
admin fuera de esas, el camino sería crear la del Partner Dashboard, no tocar código.

### 2. Variables de entorno en el servidor

```sh
SHOPIFY_EMBEDDED=true            # ya viene a true en el perfil prod; ponlo a false para desactivarlo
SHOPIFY_STORE_DOMAIN=trophypartner.myshopify.com
SHOPIFY_CLIENT_ID=...            # los mismos de siempre: son los que firman el token
SHOPIFY_CLIENT_SECRET=...
APP_AUTH_USERNAME=... APP_AUTH_PASSWORD=...   # siguen valiendo para el acceso directo
```

### 3. nginx: **quita el `auth_basic`**

Es incompatible, por dos razones: el iframe recibe un desafío de credenciales que no puede resolver,
y sobre todo **basic auth ocupa la misma cabecera `Authorization`** que necesita el session token.
Borra las dos líneas `auth_basic*` del `location /` (ya está anotado en `DEPLOY.md`). La app no queda
desprotegida: sigue teniendo su propio login, y ahora además la verificación de Shopify.

Comprueba también que nginx no añada `X-Frame-Options` — la app no lo manda y no debe mandarlo nadie.

## Comprobar que funciona

```sh
# 1. La consola declara App Bridge y la CSP:
curl -sI https://TU-DOMINIO/ | grep -i content-security-policy
curl -s  https://TU-DOMINIO/ | grep -i 'shopify-api-key\|app-bridge'

# 2. Sin credenciales sigue cerrada:
curl -s -o /dev/null -w '%{http_code}\n' https://TU-DOMINIO/api/catalog/products   # 401
```

Luego abre la app desde el admin. Si algo falla, la propia pantalla lo dice: en modo embebido no se
muestra el formulario de login (no hay contraseña que teclear ahí) sino **"Session not verified"**,
que significa que el token fue rechazado — casi siempre porque el `client_id`/`client_secret` del
servidor ya no son los de la app de Shopify, o porque `SHOPIFY_STORE_DOMAIN` no es la tienda desde la
que se abre.

## Lo que NO cambia

* La app sigue hablando con Shopify por **client_credentials** con su client id/secret. El session
  token autentica a **la persona** que abre la consola; el token de Admin API lo sigue sacando el
  servidor por su cuenta. Son cosas distintas y no se mezclan.
* El acceso directo por tu dominio funciona igual que siempre, con su login y su cookie.
* Con `SHOPIFY_EMBEDDED=false` no se carga App Bridge, no se manda la CSP y no se acepta ningún
  bearer: exactamente el comportamiento anterior.
