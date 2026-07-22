package com.trophy.promostandards.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionAuthFilter}: the static-console + health + auth-endpoint exemptions,
 * the plain-401 (no {@code WWW-Authenticate}) challenge on protected paths, and the happy path with
 * a valid session cookie.
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
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new SessionAuthFilter(auth, props).doFilter(request, response, chain);
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
}
