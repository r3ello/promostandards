package com.trophy.promostandards.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ShopifySessionToken} — the credential that replaces the cookie inside the
 * Shopify admin. Every rejection here is a way in that must stay shut: another shop's session,
 * another app's token, a forged signature, an expired one, and the {@code alg: none} classic.
 */
class ShopifySessionTokenTest {

	@Test
	void acceptsATokenForThisAppAndThisStore() {
		assertThat(EmbeddedTokens.verifier(true).valid(EmbeddedTokens.valid())).isTrue();
	}

	/** Off by default: no token is accepted until the app is deliberately configured as embedded. */
	@Test
	void acceptsNothingWhenEmbeddingIsDisabled() {
		ShopifySessionToken disabled = EmbeddedTokens.verifier(false);

		assertThat(disabled.valid(EmbeddedTokens.valid())).isFalse();
		assertThat(disabled.enabled()).isFalse();
		assertThat(disabled.apiKey()).isNull();
		assertThat(disabled.storeDomains()).isEmpty();
	}

	@Test
	void rejectsATokenSignedWithAnotherSecret() {
		String forged = EmbeddedTokens.token("https://" + EmbeddedTokens.STORE, EmbeddedTokens.CLIENT_ID,
				60, "not-our-secret");

		assertThat(EmbeddedTokens.verifier(true).valid(forged)).isFalse();
	}

	/** A single-store custom app: a genuine session of some other shop is still not our user. */
	@Test
	void rejectsAValidTokenFromAnotherShop() {
		String elsewhere = EmbeddedTokens.token("https://someone-else.myshopify.com",
				EmbeddedTokens.CLIENT_ID, 60, EmbeddedTokens.SECRET);

		assertThat(EmbeddedTokens.verifier(true).valid(elsewhere)).isFalse();
	}

	/**
	 * The store's other name. Shopify gives a store a generated permanent domain as well as the one
	 * from its name, and the session token always carries the generated one — while the Admin API
	 * answers on either, so a store can be synced for weeks through a domain its tokens never
	 * mention. That is exactly how the first live attempt failed.
	 */
	@Test
	void acceptsAnotherConfiguredNameOfTheSameStore() {
		ShopifySessionToken verifier = EmbeddedTokens.verifier(true, List.of("wy2ena-jf.myshopify.com"));
		String generated = EmbeddedTokens.token("https://wy2ena-jf.myshopify.com", EmbeddedTokens.CLIENT_ID,
				60, EmbeddedTokens.SECRET);

		assertThat(verifier.valid(generated)).isTrue();
		assertThat(verifier.valid(EmbeddedTokens.valid())).isTrue();          // the original still works
		assertThat(verifier.storeDomains())
				.containsExactly(EmbeddedTokens.STORE, "wy2ena-jf.myshopify.com");
		// and it is a list, not a licence: an unlisted shop is still refused
		assertThat(verifier.valid(EmbeddedTokens.token("https://someone-else.myshopify.com",
				EmbeddedTokens.CLIENT_ID, 60, EmbeddedTokens.SECRET))).isFalse();
	}

	@Test
	void rejectsATokenMintedForAnotherApp() {
		String otherApp = EmbeddedTokens.token("https://" + EmbeddedTokens.STORE, "another-client-id",
				60, EmbeddedTokens.SECRET);

		assertThat(EmbeddedTokens.verifier(true).valid(otherApp)).isFalse();
	}

	@Test
	void rejectsAnExpiredToken() {
		String stale = EmbeddedTokens.token("https://" + EmbeddedTokens.STORE, EmbeddedTokens.CLIENT_ID,
				-120, EmbeddedTokens.SECRET);

		assertThat(EmbeddedTokens.verifier(true).valid(stale)).isFalse();
	}

	/** The alg comes from our whitelist, never from the token — otherwise "none" verifies itself. */
	@Test
	void rejectsAnUnsignedToken() {
		assertThat(EmbeddedTokens.verifier(true).valid(EmbeddedTokens.unsignedToken())).isFalse();
	}

	/** A scheme left in shopify.store-domain must not quietly break only the embedded login. */
	@Test
	void toleratesASchemeInTheConfiguredStoreDomain() {
		ShopifySessionToken verifier = new ShopifySessionToken(
				new com.trophy.promostandards.shopify.ShopifyProperties("https://" + EmbeddedTokens.STORE + "/",
						EmbeddedTokens.CLIENT_ID, EmbeddedTokens.SECRET, null, "2026-04", null),
				new ShopifyEmbedProperties(true, List.of()));

		assertThat(verifier.valid(EmbeddedTokens.valid())).isTrue();
		assertThat(verifier.storeDomains()).containsExactly(EmbeddedTokens.STORE);   // what the CSP names
	}

	@Test
	void rejectsGarbage() {
		ShopifySessionToken verifier = EmbeddedTokens.verifier(true);

		assertThat(verifier.valid(null)).isFalse();
		assertThat(verifier.valid("")).isFalse();
		assertThat(verifier.valid("not.a.jwt")).isFalse();
		assertThat(verifier.valid("only-one-part")).isFalse();
	}

	@Test
	void readsTheTokenFromTheAuthorizationHeader() {
		ShopifySessionToken verifier = EmbeddedTokens.verifier(true);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/catalog/products");

		assertThat(verifier.authenticates(request)).isFalse();       // no header at all

		request.addHeader("Authorization", "Bearer " + EmbeddedTokens.valid());
		assertThat(verifier.authenticates(request)).isTrue();
	}

	@Test
	void ignoresANonBearerAuthorizationHeader() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/catalog/products");
		request.addHeader("Authorization", "Basic YWRtaW46c2VjcmV0");

		assertThat(EmbeddedTokens.verifier(true).authenticates(request)).isFalse();
	}
}
