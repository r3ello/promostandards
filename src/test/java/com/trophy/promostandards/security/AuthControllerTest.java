package com.trophy.promostandards.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AuthController}: the login success/failure paths (and the session cookie
 * they set/clear) and the {@code /status} check.
 */
class AuthControllerTest {

	private static AuthProperties props() {
		AuthProperties p = new AuthProperties();
		p.setEnabled(true);
		p.setUsername("admin");
		p.setPassword("secret");
		return p;
	}

	private static AuthController controller(AuthProperties props) {
		return new AuthController(new AuthService(props), props);
	}

	@Test
	void loginWithGoodCredentialsSetsSessionCookie() {
		AuthProperties props = props();
		ResponseEntity<Map<String, Object>> response =
				controller(props).login(new AuthController.LoginRequest("admin", "secret"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody()).containsEntry("authenticated", true);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains(props.getCookieName()).contains("HttpOnly").contains("Path=/");
	}

	@Test
	void loginWithBadCredentialsIsUnauthorizedWithNoCookie() {
		ResponseEntity<Map<String, Object>> response =
				controller(props()).login(new AuthController.LoginRequest("admin", "wrong"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody()).containsEntry("authenticated", false);
		assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).isNull();
	}

	@Test
	void logoutClearsCookie() {
		ResponseEntity<Map<String, Object>> response = controller(props()).logout();

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getHeaders().getFirst(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
	}

	@Test
	void statusReflectsSessionCookie() {
		AuthProperties props = props();
		AuthService auth = new AuthService(props);
		AuthController controller = new AuthController(auth, props);

		MockHttpServletRequest anonymous = new MockHttpServletRequest();
		assertThat(controller.status(anonymous)).containsEntry("authenticated", false);

		MockHttpServletRequest authed = new MockHttpServletRequest();
		authed.setCookies(new Cookie(props.getCookieName(), auth.issueToken()));
		assertThat(controller.status(authed)).containsEntry("authenticated", true);
	}
}
