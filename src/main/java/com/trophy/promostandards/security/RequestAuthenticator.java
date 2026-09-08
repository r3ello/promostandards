package com.trophy.promostandards.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The single answer to "is this request allowed in?", shared by {@link SessionAuthFilter} (which
 * blocks) and {@link AuthController} (which tells the front-end which screen to show). They used to
 * carry a copy each; with two credentials to accept, one drifting from the other would mean a
 * console that shows the login screen over an API that is perfectly happy to serve it.
 *
 * <p>Two credentials are accepted, and either is enough:
 * <ol>
 *   <li>the {@code PS_SESSION} cookie from the form login — how the console is used on its own
 *       domain;</li>
 *   <li>a Shopify <b>session token</b> in {@code Authorization: Bearer …} — how it is used inside
 *       the Shopify admin, where the cookie is third-party and never arrives
 *       ({@link ShopifySessionToken}).</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(prefix = "security.auth", name = "enabled", havingValue = "true")
public class RequestAuthenticator {

	private final AuthService auth;
	private final AuthProperties props;
	private final ShopifySessionToken shopify;

	public RequestAuthenticator(AuthService auth, AuthProperties props, ShopifySessionToken shopify) {
		this.auth = auth;
		this.props = props;
		this.shopify = shopify;
	}

	/** True when the caller presented either a valid session cookie or a valid Shopify token. */
	public boolean authenticated(HttpServletRequest request) {
		return hasValidCookie(request) || shopify.authenticates(request);
	}

	/** True when the caller is signed in through the form login specifically (so it can sign out). */
	public boolean hasValidCookie(HttpServletRequest request) {
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
}
