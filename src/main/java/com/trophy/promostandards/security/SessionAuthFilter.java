package com.trophy.promostandards.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Session-cookie gate over the app's data + management endpoints, backing the static login screen.
 *
 * <p>Registered only when {@code security.auth.enabled=true}. Unlike HTTP Basic (which pops the
 * browser's native credential dialog), this filter protects <em>only</em> the sensitive endpoints
 * and lets the static console shell load unauthenticated, so {@code index.html} can render its own
 * login screen. That screen posts to {@code /api/auth/login} (see {@link AuthController}), which
 * sets the session cookie this filter checks.
 *
 * <p>Protected: {@code /api/**} (except {@code /api/auth/**}) and {@code /actuator/**} (except
 * {@code /actuator/health}, left open for the Docker healthcheck). A protected request without a
 * valid session cookie gets a plain {@code 401} JSON body and <em>no</em> {@code WWW-Authenticate}
 * header, so no browser dialog appears — the front-end shows the login screen instead.
 */
@Component
@ConditionalOnProperty(prefix = "security.auth", name = "enabled", havingValue = "true")
public class SessionAuthFilter extends OncePerRequestFilter {

	/** Path prefix left open so the container healthcheck (see Dockerfile) needs no credentials. */
	private static final String HEALTH_PATH = "/actuator/health";

	private final AuthService auth;
	private final AuthProperties props;

	public SessionAuthFilter(AuthService auth, AuthProperties props) {
		this.auth = auth;
		this.props = props;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		if (!isProtected(pathWithinApp(request)) || hasValidSession(request)) {
			chain.doFilter(request, response);
			return;
		}
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType("application/json");
		response.getWriter().write("{\"authenticated\":false,\"message\":\"Authentication required\"}");
	}

	/** Sensitive endpoints require a session; the login endpoints and the static console do not. */
	private boolean isProtected(String path) {
		if (path.equals("/api/auth") || path.startsWith("/api/auth/")) {
			return false; // login/logout/status must be reachable without a session
		}
		if (path.startsWith("/api/")) {
			return true; // all supplier + sync data and actions
		}
		if (path.equals(HEALTH_PATH) || path.startsWith(HEALTH_PATH + "/")) {
			return false;
		}
		return path.equals("/actuator") || path.startsWith("/actuator/");
	}

	private boolean hasValidSession(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return false;
		}
		for (Cookie cookie : cookies) {
			if (props.getCookieName().equals(cookie.getName()) && auth.tokenValid(cookie.getValue())) {
				return true;
			}
		}
		return false;
	}

	private static String pathWithinApp(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		return path;
	}
}
