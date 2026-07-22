package com.trophy.promostandards.security;

import jakarta.servlet.http.Cookie;
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
 */
@RestController
@RequestMapping("/api/auth")
@ConditionalOnProperty(prefix = "security.auth", name = "enabled", havingValue = "true")
public class AuthController {

	private final AuthService auth;
	private final AuthProperties props;

	public AuthController(AuthService auth, AuthProperties props) {
		this.auth = auth;
		this.props = props;
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
	 * Tells the front-end whether the session is still valid, so it can show either the login screen
	 * or the console on load without triggering a browser credential dialog.
	 */
	@GetMapping("/status")
	public Map<String, Object> status(HttpServletRequest request) {
		boolean authenticated = hasValidSession(request);
		Map<String, Object> out = new HashMap<>();
		out.put("enabled", true);
		out.put("authenticated", authenticated);
		if (authenticated) {
			out.put("username", props.getUsername());
		}
		return out;
	}

	private boolean hasValidSession(HttpServletRequest request) {
		if (request.getCookies() == null) {
			return false;
		}
		for (Cookie cookie : request.getCookies()) {
			if (props.getCookieName().equals(cookie.getName()) && auth.tokenValid(cookie.getValue())) {
				return true;
			}
		}
		return false;
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
