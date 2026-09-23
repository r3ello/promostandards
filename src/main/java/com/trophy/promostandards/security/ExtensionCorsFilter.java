package com.trophy.promostandards.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Lets a Shopify <b>admin UI extension</b> call these endpoints from the browser.
 *
 * <p>The "Send to PaceSetter" action on a Shopify order is an extension of another app of the same
 * store, and Shopify serves extensions from {@code https://extensions.shopifycdn.com} — a different
 * origin from this server, so its {@code fetch} is a cross-origin request. Two things follow, and
 * without either one the call never arrives:
 *
 * <ul>
 *   <li>the answer must carry {@code Access-Control-Allow-Origin} for that origin, or the browser
 *       hands the extension a network error and nothing reaches the page;</li>
 *   <li>the browser sends an {@code OPTIONS} <b>preflight</b> first, and it carries <b>no
 *       credentials</b> — no {@code Authorization} header, no cookie. {@link SessionAuthFilter} would
 *       answer it 401, which the browser reports as a CORS failure rather than as an auth problem, so
 *       the preflight is answered here and never reaches the gate.</li>
 * </ul>
 *
 * <p>Only the origins below are allowed, and only for {@code /api/**}. No
 * {@code Access-Control-Allow-Credentials}: the extension authenticates with a session token in the
 * {@code Authorization} header, so nothing needs the cookie to travel cross-site — and saying so
 * would let any page that can read the cookie speak for the user.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ExtensionCorsFilter extends OncePerRequestFilter {

	/** Where Shopify serves admin UI extensions from; the admin itself frames the console instead. */
	private static final List<String> ALLOWED_ORIGINS = List.of("https://extensions.shopifycdn.com");
	private static final String ALLOWED_METHODS = "GET, POST, OPTIONS";
	private static final String ALLOWED_HEADERS = "Authorization, Content-Type";
	/** How long a browser may reuse one preflight, in seconds. */
	private static final String MAX_AGE = "600";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String origin = request.getHeader(HttpHeaders.ORIGIN);
		boolean allowed = origin != null && ALLOWED_ORIGINS.contains(origin) && isApi(request);

		if (allowed) {
			response.setHeader("Access-Control-Allow-Origin", origin);
			// Without Vary the answer to one origin can be cached and served to another.
			response.addHeader(HttpHeaders.VARY, HttpHeaders.ORIGIN);
			response.setHeader("Access-Control-Allow-Methods", ALLOWED_METHODS);
			response.setHeader("Access-Control-Allow-Headers", ALLOWED_HEADERS);
			response.setHeader("Access-Control-Max-Age", MAX_AGE);
		}

		if (allowed && "OPTIONS".equalsIgnoreCase(request.getMethod())
				&& request.getHeader("Access-Control-Request-Method") != null) {
			response.setStatus(HttpServletResponse.SC_NO_CONTENT);
			return;   // the preflight carries no credential; answering it is not letting anyone in
		}
		chain.doFilter(request, response);
	}

	private static boolean isApi(HttpServletRequest request) {
		String path = request.getRequestURI();
		String context = request.getContextPath();
		if (context != null && !context.isEmpty() && path.startsWith(context)) {
			path = path.substring(context.length());
		}
		return path.startsWith("/api/");
	}
}
