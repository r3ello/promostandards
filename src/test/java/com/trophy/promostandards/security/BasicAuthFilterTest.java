package com.trophy.promostandards.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BasicAuthFilter}: the health exemption, the challenge on missing/bad
 * credentials, the happy path, and the fail-closed behaviour when no password is configured.
 */
class BasicAuthFilterTest {

	private static AuthProperties props(String user, String password) {
		AuthProperties p = new AuthProperties();
		p.setEnabled(true);
		p.setRealm("PromoStandards");
		p.setUsername(user);
		p.setPassword(password);
		return p;
	}

	private static String basic(String user, String password) {
		String raw = user + ":" + password;
		return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static MockHttpServletResponse run(AuthProperties props, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new BasicAuthFilter(props).doFilter(request, response, chain);
		return response;
	}

	@Test
	void allowsHealthCheckWithoutCredentials() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
		MockHttpServletResponse response = run(props("admin", "secret"), request);

		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void challengesWhenNoAuthorizationHeader() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/inventory/PRD/levels");
		MockHttpServletResponse response = run(props("admin", "secret"), request);

		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getHeader("WWW-Authenticate")).contains("Basic realm=\"PromoStandards\"");
	}

	@Test
	void rejectsWrongPassword() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
		request.addHeader("Authorization", basic("admin", "nope"));
		MockHttpServletResponse response = run(props("admin", "secret"), request);

		assertThat(response.getStatus()).isEqualTo(401);
	}

	@Test
	void acceptsCorrectCredentials() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products/PRD");
		request.addHeader("Authorization", basic("admin", "secret"));
		MockHttpServletResponse response = run(props("admin", "secret"), request);

		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void failsClosedWhenPasswordNotConfigured() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
		request.addHeader("Authorization", basic("admin", ""));
		MockHttpServletResponse response = run(props("admin", ""), request);

		assertThat(response.getStatus()).isEqualTo(401);
	}
}
