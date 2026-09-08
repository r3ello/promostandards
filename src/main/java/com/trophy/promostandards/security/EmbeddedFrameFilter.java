package com.trophy.promostandards.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Declares who may put this app in an iframe, which is what lets the Shopify admin render it.
 *
 * <p>Shopify requires an embedded app to answer with
 * {@code Content-Security-Policy: frame-ancestors https://<shop> https://admin.shopify.com} — and
 * with nothing that contradicts it, which is why the app must never send {@code X-Frame-Options}
 * (it doesn't: there is no Spring Security on the classpath adding one).
 *
 * <p>The header is sent on every response, not just the HTML shell, so a 401 or an error page is
 * still rendered inside the admin rather than silently blanking the frame. It runs first in the
 * chain for the same reason: {@link SessionAuthFilter} can end a request early.
 *
 * <p>When embedding is off the filter adds nothing — no header at all, which is the historical
 * behaviour of this app served on its own domain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EmbeddedFrameFilter extends OncePerRequestFilter {

	private final ShopifySessionToken shopify;

	public EmbeddedFrameFilter(ShopifySessionToken shopify) {
		this.shopify = shopify;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String shop = shopify.storeDomain();
		if (shop != null) {
			response.setHeader("Content-Security-Policy",
					"frame-ancestors https://" + shop + " https://admin.shopify.com;");
		}
		chain.doFilter(request, response);
	}
}
