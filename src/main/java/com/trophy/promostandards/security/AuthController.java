package com.trophy.promostandards.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * Form-login endpoints backing the static console's login screen. Present only when
 * {@code security.auth.enabled=true}. Credentials are checked by {@link AuthService}; a success
 * sets the HttpOnly session cookie that {@link SessionAuthFilter} verifies on every protected call.
 *
 * <p>Inside the Shopify admin nobody signs in here at all: the console authenticates with a Shopify
 * session token and {@code /status} reports it as authenticated on the strength of that
 * ({@link RequestAuthenticator}). The login form stays for direct access on this app's own domain.
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "security.auth", name = "enabled", havingValue = "true")
public class AuthController {

	private final AuthService auth;
	private final AuthProperties props;
	private final RequestAuthenticator authenticator;
	private final ShopifySessionToken shopify;

	public AuthController(AuthService auth, AuthProperties props, RequestAuthenticator authenticator,
			ShopifySessionToken shopify) {
		this.auth = auth;
		this.props = props;
		this.authenticator = authenticator;
		this.shopify = shopify;
	}

	/** Login payload posted by the console's sign-in form. */
	public record LoginRequest(String username, String password) {
	}

	@PostMapping("/login")
	public ResponseEntity<Map<String, Object>> login(@RequestBody(required = false) LoginRequest body) {
		String username = body == null ? null : body.username();
		String password = body == null ? null : body.password();
		if (!auth.credentialsValid(username, password)) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("authenticated", false, "message", "Invalid username or password"));
		}
		ResponseCookie cookie = sessionCookie(auth.issueToken(), props.getSessionMinutes() * 60L);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookie.toString())
				.body(Map.of("authenticated", true, "username", props.getUsername()));
	}

	@PostMapping("/logout")
	public ResponseEntity<Map<String, Object>> logout() {
		ResponseCookie cleared = sessionCookie("", 0);
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cleared.toString())
				.body(Map.of("authenticated", false));
	}

	/**
	 * Tells the front-end whether the caller is already in, so it can show either the login screen
	 * or the console on load without triggering a browser credential dialog. {@code embedded} says
	 * the app is running as a Shopify embedded app; {@code signOut} is false in that case because
	 * the session belongs to the Shopify admin, not to this app.
	 */
	@GetMapping("/status")
	public Map<String, Object> status(HttpServletRequest request) {
		boolean cookie = authenticator.hasValidCookie(request);
		boolean authenticated = cookie || authenticator.authenticated(request);
		Map<String, Object> out = new HashMap<>();
		out.put("enabled", true);
		out.put("embedded", shopify.enabled());
		out.put("authenticated", authenticated);
		out.put("signOut", cookie);
		if (authenticated) {
			out.put("username", cookie ? props.getUsername() : "Shopify admin");
		} else if (shopify.enabled()) {
			// The console is about to draw "session not verified"; give it something to say. This
			// endpoint is open by design, and the reason names configuration, never a credential.
			out.put("tokenError", shopify.refusal(request));
		}
		return out;
	}

	private ResponseCookie sessionCookie(String value, long maxAgeSeconds) {
		return ResponseCookie.from(props.getCookieName(), value)
				.httpOnly(true)
				.secure(props.isCookieSecure())
				.path("/")
				.maxAge(maxAgeSeconds)
				.sameSite("Lax")
				.build();
	}
}
