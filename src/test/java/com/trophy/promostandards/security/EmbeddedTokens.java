package com.trophy.promostandards.security;

import com.trophy.promostandards.shopify.ShopifyProperties;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

/**
 * Test fixture that plays Shopify: it mints the session tokens App Bridge would hand the browser,
 * so {@link ShopifySessionToken} can be exercised without the admin.
 */
final class EmbeddedTokens {

	static final String STORE = "trophypartner.myshopify.com";
	static final String CLIENT_ID = "0123456789abcdef";
	static final String SECRET = "shopify-client-secret";

	private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

	private EmbeddedTokens() {
	}

	/** A verifier wired to {@link #STORE} / {@link #CLIENT_ID}, embedded mode on or off. */
	static ShopifySessionToken verifier(boolean enabled) {
		return verifier(enabled, List.of());
	}

	/** The same, also accepting {@code alsoAccepted} as names of the same store. */
	static ShopifySessionToken verifier(boolean enabled, List<String> alsoAccepted) {
		return new ShopifySessionToken(
				new ShopifyProperties(STORE, CLIENT_ID, SECRET, null, "2026-04", null),
				new ShopifyEmbedProperties(enabled, alsoAccepted));
	}

	/** The token Shopify would issue: this store, this app, valid now. */
	static String valid() {
		return token("https://" + STORE, CLIENT_ID, 60, SECRET);
	}

	/**
	 * @param dest      the {@code dest} claim (and the base of {@code iss}) — the shop the session
	 *                  belongs to
	 * @param audience  the {@code aud} claim — the app the token was minted for
	 * @param expiresIn seconds until {@code exp}; negative for an already-expired token
	 * @param secret    the signing secret; anything but {@link #SECRET} forges the signature
	 */
	static String token(String dest, String audience, long expiresIn, String secret) {
		long now = Instant.now().getEpochSecond();
		String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String claims = B64.encodeToString(("{"
				+ "\"iss\":\"" + dest + "/admin\","
				+ "\"dest\":\"" + dest + "\","
				+ "\"aud\":\"" + audience + "\","
				+ "\"sub\":\"42\","
				+ "\"exp\":" + (now + expiresIn) + ","
				+ "\"nbf\":" + (now - 30) + ","
				+ "\"iat\":" + (now - 30) + ","
				+ "\"jti\":\"abc\""
				+ "}").getBytes(StandardCharsets.UTF_8));
		return header + "." + claims + "." + B64.encodeToString(sign(header + "." + claims, secret));
	}

	/** Same claims, but signed with an algorithm the verifier must refuse outright. */
	static String unsignedToken() {
		String header = B64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
		String body = valid().split("\\.")[1];
		return header + "." + body + ".";
	}

	private static byte[] sign(String data, String secret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
