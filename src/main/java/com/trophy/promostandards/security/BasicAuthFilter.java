package com.trophy.promostandards.security;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * Minimal HTTP Basic authentication for the entire application (static console + REST API).
 *
 * <p>Registered as a servlet filter only when {@code security.auth.enabled=true}; when disabled
 * (the default, including all tests) the bean is never created and requests pass through untouched.
 * Credentials are the single {@code security.auth.username}/{@code password} pair — see
 * {@link AuthProperties}.
 *
 * <p>{@code /actuator/health} is always allowed through so the Docker container healthcheck keeps
 * working without credentials. Everything else requires a valid {@code Authorization: Basic} header;
 * missing/invalid credentials get a {@code 401} with a {@code WWW-Authenticate} challenge, which is
 * what triggers the browser's login prompt.
 */
@Component
@ConditionalOnProperty(prefix = "security.auth", name = "enabled", havingValue = "true")
public class BasicAuthFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(BasicAuthFilter.class);

	/** Path prefix left open so the container healthcheck (see Dockerfile) needs no credentials. */
	private static final String HEALTH_PATH = "/actuator/health";

	private static final String BASIC_PREFIX = "Basic ";

	private final AuthProperties props;

	public BasicAuthFilter(AuthProperties props) {
		this.props = props;
	}

	@PostConstruct
	void logStatus() {
		if (isPasswordMissing()) {
			log.warn("security.auth.enabled=true but no password is configured (set APP_AUTH_PASSWORD). "
					+ "All requests will be denied with 401 until a password is set.");
		} else {
			log.info("HTTP Basic auth is ON (realm='{}', username='{}').", props.getRealm(), props.getUsername());
		}
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		if (isHealthCheck(request) || isAuthenticated(request)) {
			chain.doFilter(request, response);
			return;
		}

		response.setHeader("WWW-Authenticate", "Basic realm=\"" + props.getRealm() + "\", charset=\"UTF-8\"");
		response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required");
	}

	private boolean isHealthCheck(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		return path.equals(HEALTH_PATH) || path.startsWith(HEALTH_PATH + "/");
	}

	private boolean isAuthenticated(HttpServletRequest request) {
		if (isPasswordMissing()) {
			return false; // fail closed: an enabled-but-unconfigured gate must not let anyone in
		}
		String header = request.getHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
			return false;
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(header.substring(BASIC_PREFIX.length()).trim());
		} catch (IllegalArgumentException malformedBase64) {
			return false;
		}
		String credentials = new String(decoded, StandardCharsets.UTF_8);
		int separator = credentials.indexOf(':');
		if (separator < 0) {
			return false;
		}
		String user = credentials.substring(0, separator);
		String pass = credentials.substring(separator + 1);
		// non-short-circuit '&' so both comparisons always run (avoids leaking which field mismatched)
		return constantTimeEquals(user, props.getUsername()) & constantTimeEquals(pass, props.getPassword());
	}

	private boolean isPasswordMissing() {
		return props.getPassword() == null || props.getPassword().isBlank();
	}

	private static boolean constantTimeEquals(String a, String b) {
		if (a == null || b == null) {
			return false;
		}
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}
}
