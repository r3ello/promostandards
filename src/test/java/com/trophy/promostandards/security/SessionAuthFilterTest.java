package com.trophy.promostandards.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionAuthFilter}: the static-console + health + auth-endpoint exemptions,
 * the plain-401 (no {@code WWW-Authenticate}) challenge on protected paths, and both ways in — the
 * session cookie and, in embedded mode, a Shopify session token.
 */
class SessionAuthFilterTest {

	private static AuthProperties props() {
		AuthProperties p = new AuthProperties();
		p.setEnabled(true);
		p.setUsername("admin");
		p.setPassword("secret");
		return p;
	}

	private static MockHttpServletResponse run(AuthService auth, AuthProperties props, MockHttpServletRequest request)
			throws Exception {
		return run(auth, props, EmbeddedTokens.verifier(false), request);
	}

	private static MockHttpServletResponse run(AuthService auth, AuthProperties props, ShopifySessionToken shopify,
			MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new SessionAuthFilter(new RequestAuthenticator(auth, props, shopify)).doFilter(request, response, chain);
		return response;
	}

	@Test
	void allowsStaticConsoleWithoutSession() throws Exception {
		AuthProperties props = props();
		MockHttpServletResponse response = run(new AuthService(props), props, new MockHttpServletRequest("GET", "/"));
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void allowsHealthAndAuthEndpointsWithoutSession() throws Exception {
		AuthProperties props = props();
		AuthService auth = new AuthService(props);
		assertThat(run(auth, props, new MockHttpServletRequest("GET", "/actuator/health")).getStatus()).isEqualTo(200);
		assertThat(run(auth, props, new MockHttpServletRequest("POST", "/api/auth/login")).getStatus()).isEqualTo(200);
		assertThat(run(auth, props, new MockHttpServletRequest("GET", "/api/auth/status")).getStatus()).isEqualTo(200);
	}

	@Test
	void challengesProtectedPathWithoutSession() throws Exception {
		AuthProperties props = props();
		MockHttpServletResponse response = run(new AuthService(props), props,
				new MockHttpServletRequest("GET", "/api/products/PRD"));

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("WWW-Authenticate")).isNull(); // no browser credential dialog
		assertThat(response.getContentAsString()).contains("\"authenticated\":false");
	}

	@Test
	void allowsProtectedPathWithValidSessionCookie() throws Exception {
		AuthProperties props = props();
		AuthService auth = new AuthService(props);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products/PRD");
		request.setCookies(new Cookie(props.getCookieName(), auth.issueToken()));

		assertThat(run(auth, props, request).getStatus()).isEqualTo(200);
	}

	@Test
	void rejectsProtectedPathWithInvalidSessionCookie() throws Exception {
		AuthProperties props = props();
		AuthService auth = new AuthService(props);
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/inventory/PRD/levels");
		request.setCookies(new Cookie(props.getCookieName(), "forged-token"));

		assertThat(run(auth, props, request).getStatus()).isEqualTo(401);
	}

	/** How the console gets in from inside the Shopify admin, where the cookie never arrives. */
	@Test
	void allowsProtectedPathWithAShopifySessionToken() throws Exception {
		AuthProperties props = props();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/catalog/products");
		request.addHeader("Authorization", "Bearer " + EmbeddedTokens.valid());

		assertThat(run(new AuthService(props), props, EmbeddedTokens.verifier(true), request).getStatus())
				.isEqualTo(200);
	}

	/** The same token is worth nothing until the app is configured as an embedded app. */
	@Test
	void ignoresAShopifySessionTokenWhenEmbeddingIsOff() throws Exception {
		AuthProperties props = props();
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/catalog/products");
		request.addHeader("Authorization", "Bearer " + EmbeddedTokens.valid());

		assertThat(run(new AuthService(props), props, request).getStatus()).isEqualTo(401);
	}
}
