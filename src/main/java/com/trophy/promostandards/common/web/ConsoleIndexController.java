package com.trophy.promostandards.common.web;

import com.trophy.promostandards.security.ShopifySessionToken;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Serves the console shell, and it exists for one reason: <b>App Bridge needs this app's client id
 * inside the HTML</b>, and {@code static/index.html} is a flat file with no way to hold it.
 *
 * <p>So the shell keeps a {@code <!--SHOPIFY_APP_BRIDGE-->} marker in its {@code <head>} and this
 * controller fills it in — with the App Bridge script and the {@code shopify-api-key} meta tag when
 * the app is configured as embedded, with nothing at all when it is not. Injecting it here rather
 * than from {@code app.js} is deliberate: Shopify wants the App Bridge script to be the first script
 * in the document, and a script the page adds later races with the first call that needs a token.
 *
 * <p>Mapping {@code /} in a controller takes precedence over Boot's welcome-page handler, so the
 * static file is never served raw — which matters, because raw it would carry the literal marker
 * and no way to authenticate inside the admin.
 */
@Controller
public class ConsoleIndexController {

	private static final String MARKER = "<!--SHOPIFY_APP_BRIDGE-->";
	private static final String APP_BRIDGE_SRC = "https://cdn.shopify.com/shopifycloud/app-bridge.js";

	private final ShopifySessionToken shopify;
	/** The rendered shell. Config cannot change without a restart, so once is enough. */
	private volatile String cached;

	public ConsoleIndexController(ShopifySessionToken shopify) {
		this.shopify = shopify;
	}

	@GetMapping(path = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
	@ResponseBody
	public ResponseEntity<String> index() throws IOException {
		String html = cached;
		if (html == null) {
			cached = html = render();
		}
		return ResponseEntity.ok()
				// The shell carries the api key and decides which auth path runs; never let a proxy
				// or the admin's frame reuse one rendered under a different configuration.
				.cacheControl(CacheControl.noStore())
				.contentType(MediaType.valueOf("text/html;charset=UTF-8"))
				.body(html);
	}

	private String render() throws IOException {
		ClassPathResource shell = new ClassPathResource("static/index.html");
		String html = new String(shell.getContentAsByteArray(), StandardCharsets.UTF_8);
		String apiKey = shopify.apiKey();
		if (apiKey == null) {
			return html.replace(MARKER, "");
		}
		return html.replace(MARKER,
				"<meta name=\"shopify-api-key\" content=\"" + attribute(apiKey) + "\">\n"
						+ "\t<script src=\"" + APP_BRIDGE_SRC + "\"></script>");
	}

	/** The client id is configuration, not user input, but it still goes into an HTML attribute. */
	private static String attribute(String value) {
		return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
