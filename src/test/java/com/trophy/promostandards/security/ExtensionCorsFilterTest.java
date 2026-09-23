package com.trophy.promostandards.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * What lets the order action in the store's other app reach these endpoints from a browser: the CORS
 * answer, and a preflight that is allowed to arrive without a credential — it carries none.
 */
class ExtensionCorsFilterTest {

	private static final String EXTENSIONS = "https://extensions.shopifycdn.com";

	private final ExtensionCorsFilter filter = new ExtensionCorsFilter();

	private MockHttpServletRequest request(String method, String path, String origin) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		if (origin != null) {
			request.addHeader("Origin", origin);
		}
		return request;
	}

	@Test
	void answersAnExtensionsCallWithTheHeadersTheBrowserNeeds() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request("GET", "/api/orders/pacesetter-pending", EXTENSIONS), response, chain);

		assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo(EXTENSIONS);
		assertThat(response.getHeader("Access-Control-Allow-Headers")).contains("Authorization");
		assertThat(response.getHeaders("Vary")).contains("Origin");
		// No Allow-Credentials: the extension proves itself with a session token, not with the cookie.
		assertThat(response.getHeader("Access-Control-Allow-Credentials")).isNull();
		assertThat(chain.getRequest()).isNotNull();          // and the request went through
	}

	/**
	 * The preflight has no Authorization header and no cookie, so the session gate would answer it
	 * 401 — which a browser reports as a CORS failure, hiding the real cause. It is answered here and
	 * never reaches the gate, which lets nobody in: a preflight carries no request to perform.
	 */
	@Test
	void answersThePreflightItselfWithoutPassingItOn() throws Exception {
		MockHttpServletRequest preflight = request("OPTIONS", "/api/orders/1046/pacesetter-po", EXTENSIONS);
		preflight.addHeader("Access-Control-Request-Method", "POST");
		preflight.addHeader("Access-Control-Request-Headers", "authorization,content-type");
		MockHttpServletResponse response = new MockHttpServletResponse();
		FilterChain chain = mock(FilterChain.class);

		filter.doFilter(preflight, response, chain);

		assertThat(response.getStatus()).isEqualTo(204);
		assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("POST");
		verifyNoInteractions(chain);
	}

	@Test
	void saysNothingToAnyoneElse() throws Exception {
		for (String[] call : new String[][] {
				{"GET", "/api/orders/pacesetter-pending", "https://evil.example.com"},
				{"GET", "/api/orders/pacesetter-pending", null},
				// Outside /api/** there is nothing to reach: the console shell is plain static files.
				{"GET", "/index.html", EXTENSIONS}}) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();

			filter.doFilter(request(call[0], call[1], call[2]), response, chain);

			assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
			assertThat(chain.getRequest()).isNotNull();
		}
	}

	/** An OPTIONS that is not a preflight is a normal request and stays one. */
	@Test
	void passesOnAnOptionsThatIsNotAPreflight() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();

		filter.doFilter(request("OPTIONS", "/api/orders/pacesetter-pending", EXTENSIONS), response, chain);

		assertThat(chain.getRequest()).isNotNull();
		assertThat(response.getStatus()).isEqualTo(200);
	}
}
