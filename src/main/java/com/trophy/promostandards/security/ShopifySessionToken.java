package com.trophy.promostandards.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trophy.promostandards.shopify.ShopifyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;

/**
 * Verifier for the <b>Shopify session token</b> an embedded app receives from App Bridge.
 *
 * <p>App Bridge hands the browser a JWT signed <b>HS256 with this app's client secret</b>; the
 * front-end sends it as {@code Authorization: Bearer …} on every call and we check it here. Since
 * only Shopify and this server know the secret, a token that verifies proves the caller is a real
 * admin session of the configured store — which is a stronger statement than the shared
 * username/password the form login checks, and the only one that works in the admin's iframe
 * (see {@link ShopifyEmbedProperties}).
 *
 * <p>What is checked, and why each one matters:
 * <ul>
 *   <li><b>alg = HS256</b>, taken from a whitelist rather than from the token — a verifier that
 *       trusts the token's own {@code alg} can be told {@code none}.</li>
 *   <li><b>signature</b> over {@code header.payload} with the client secret.</li>
 *   <li><b>{@code aud} = our client id</b> — a valid token minted for a different app is not ours.</li>
 *   <li><b>{@code dest} = our store</b>, and {@code iss} under it — this is a single-store custom
 *       app, so a session from any other shop is refused rather than let in as "some Shopify user".</li>
 *   <li><b>{@code exp} / {@code nbf}</b>, with a few seconds of leeway for clock drift. Session
 *       tokens live about a minute, which is why the front-end asks for a fresh one per request.</li>
 * </ul>
 *
 * <p><b>Every refusal carries a reason</b> ({@link #refusal(HttpServletRequest)}), which the console
 * shows and the log records. That is not decoration: from the outside a wrong shop, a wrong app, a
 * server clock and a proxy eating the header all look like the same blank "not verified" screen, and
 * the first deploy lost an afternoon to exactly that.
 *
 * <p>The bean always exists; when embedding is off (or the credentials are missing) it simply
 * refuses everything, so nothing has to be conditional on the feature switch.
 */
@Component
public class ShopifySessionToken {

	private static final Logger log = LoggerFactory.getLogger(ShopifySessionToken.class);
	private static final String HMAC_ALG = "HmacSHA256";
	private static final String BEARER = "Bearer ";
	/** Tolerated clock drift between Shopify's signer and this server, in seconds. */
	private static final long LEEWAY = 10;

	private final ShopifyProperties shopify;
	private final ShopifyEmbedProperties embed;
	private final ObjectMapper json = new ObjectMapper();

	public ShopifySessionToken(ShopifyProperties shopify, ShopifyEmbedProperties embed) {
		this.shopify = shopify;
		this.embed = embed;
	}

	/** True when the app is configured to run inside the Shopify admin and can verify tokens. */
	public boolean enabled() {
		return embed.enabled() && filled(shopify.clientId()) && filled(shopify.clientSecret())
				&& filled(shopify.storeDomain());
	}

	/**
	 * The client id, which App Bridge needs in the page and which is public by design (it travels in
	 * every embedded URL). Null when embedding is off, so the console omits App Bridge entirely.
	 */
	public String apiKey() {
		return enabled() ? shopify.clientId() : null;
	}

	/** The {@code *.myshopify.com} domain allowed to frame this app; null when embedding is off. */
	public String storeDomain() {
		return enabled() ? host() : null;
	}

	/** True when the request carries a session token this app minted-for and trusts. */
	public boolean authenticates(HttpServletRequest request) {
		String refusal = refusal(request);
		if (refusal != null && request.getHeader(HttpHeaders.AUTHORIZATION) != null) {
			// Only when something was actually presented: an anonymous call is not an incident.
			log.warn("Shopify session token refused: {}", refusal);
		}
		return refusal == null;
	}

	/**
	 * Why this request's Shopify credential was not accepted, or {@code null} when it was.
	 *
	 * <p>The wording is aimed at whoever is staring at the admin wondering why it will not open, so
	 * it names the likely cause rather than the failed check alone.
	 */
	public String refusal(HttpServletRequest request) {
		if (!enabled()) {
			return "this server is not configured as an embedded Shopify app "
					+ "(shopify.embedded.enabled, or a missing client id / secret / store domain)";
		}
		String header = request.getHeader(HttpHeaders.AUTHORIZATION);
		if (header == null) {
			return "no Authorization header reached the app — the console sends one on every call, so "
					+ "something in front of it (nginx auth_basic?) is stripping or replacing it";
		}
		if (!header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
			return "the Authorization header is not a Bearer token but '" + scheme(header) + "' — basic "
					+ "auth on the proxy occupies the very header the session token travels in";
		}
		return refuse(header.substring(BEARER.length()).trim());
	}

	/** True when {@code token} is a well-formed, unexpired session token for this app and store. */
	public boolean valid(String token) {
		return refuse(token) == null;
	}

	/** The reason {@code token} is not acceptable, or null when it is. */
	private String refuse(String token) {
		if (!enabled()) {
			return "embedded mode is off";
		}
		if (token == null || token.isBlank()) {
			return "empty bearer token";
		}
		String[] parts = token.split("\\.");
		if (parts.length != 3) {
			return "not a three-part JWT (" + parts.length + " segments) — is this really a Shopify "
					+ "session token?";
		}
		try {
			JsonNode header = json.readTree(decode(parts[0]));
			String alg = header.path("alg").asText();
			if (!"HS256".equals(alg)) {
				return "unsupported signing algorithm '" + alg + "'";
			}
			byte[] expected = sign((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			if (!MessageDigest.isEqual(expected, Base64.getUrlDecoder().decode(parts[2]))) {
				return "bad signature — the shopify.client-secret on this server is not the secret of "
						+ "the Shopify app the admin loaded";
			}
			return refuseClaims(json.readTree(decode(parts[1])));
		} catch (IllegalArgumentException | java.io.IOException malformed) {
			return "malformed token: " + malformed.getMessage();
		}
	}

	private String refuseClaims(JsonNode claims) {
		String expectedShop = "https://" + host();
		String dest = trimSlash(claims.path("dest").asText().toLowerCase(Locale.ROOT));
		if (!expectedShop.equals(dest)) {
			return "the token is for " + dest + " but this server is configured for " + expectedShop
					+ " (shopify.store-domain)";
		}
		if (!claims.path("iss").asText().toLowerCase(Locale.ROOT).startsWith(expectedShop)) {
			return "the token's issuer does not belong to " + expectedShop;
		}
		String audience = claims.path("aud").asText();
		if (!shopify.clientId().equals(audience)) {
			return "the token was minted for app " + audience + ", not for the client id this server "
					+ "uses (shopify.client-id)";
		}
		long now = Instant.now().getEpochSecond();
		long expiry = claims.path("exp").asLong();
		if (expiry + LEEWAY < now) {
			long ago = now - expiry;
			// Session tokens live ~1 minute, so anything past a couple of them is a clock, not a delay.
			return "the token expired " + ago + "s ago"
					+ (ago > 120 ? " — that is far more than a session token's lifetime, so check the "
					+ "server's clock (it thinks it is " + Instant.ofEpochSecond(now) + ")" : "");
		}
		long notBefore = claims.path("nbf").asLong();
		if (notBefore - LEEWAY > now) {
			return "the token is not valid for another " + (notBefore - now) + "s — this server's clock "
					+ "is behind Shopify's (it thinks it is " + Instant.ofEpochSecond(now) + ")";
		}
		return null;
	}

	/**
	 * The configured store as a bare lowercase host. A {@code https://} prefix or a trailing slash
	 * is tolerated on purpose: this one value is both compared against the token's {@code dest} and
	 * written into the frame-ancestors header, so a scheme left in the config would break the
	 * embedded login while every other Shopify call kept working — a long way to go looking for a
	 * stray "https://".
	 */
	private String host() {
		String domain = shopify.storeDomain().trim().toLowerCase(Locale.ROOT);
		if (domain.startsWith("https://")) {
			domain = domain.substring("https://".length());
		}
		return trimSlash(domain);
	}

	/** The scheme word of an Authorization header, for the message — never the credential itself. */
	private static String scheme(String header) {
		int space = header.indexOf(' ');
		return space > 0 ? header.substring(0, space) : header.substring(0, Math.min(header.length(), 12));
	}

	private static String trimSlash(String value) {
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

	private static String decode(String segment) {
		return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
	}

	private byte[] sign(byte[] data) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALG);
			mac.init(new SecretKeySpec(shopify.clientSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALG));
			return mac.doFinal(data);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
		}
	}

	private static boolean filled(String value) {
		return value != null && !value.isBlank();
	}
}
