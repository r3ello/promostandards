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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
		String html = withAssetVersions(read("static/index.html"));
		String apiKey = shopify.apiKey();
		if (apiKey == null) {
			return html.replace(MARKER, "");
		}
		return html.replace(MARKER,
				"<meta name=\"shopify-api-key\" content=\"" + attribute(apiKey) + "\">\n"
						+ "\t<script src=\"" + APP_BRIDGE_SRC + "\"></script>");
	}

	/**
	 * Stamps the stylesheet/script links with a hash of their own content, so a deploy is enough to
	 * make every browser fetch them again.
	 *
	 * <p>Worth the few lines: the first embedded deploy showed the login form and the "session not
	 * verified" card <em>at the same time</em>, because the browser was still running the previous
	 * {@code app.css} — the one without the rule that hides whichever card is not wanted. A stale
	 * asset there does not look like a cache, it looks like the feature is broken.
	 */
	private String withAssetVersions(String html) throws IOException {
		String version = version("static/app.css", "static/app.js", "static/polaris-tokens.css");
		return html.replace("href=\"app.css\"", "href=\"app.css?v=" + version + "\"")
				.replace("href=\"polaris-tokens.css\"", "href=\"polaris-tokens.css?v=" + version + "\"")
				.replace("src=\"app.js\"", "src=\"app.js?v=" + version + "\"");
	}

	private String version(String... resources) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (String resource : resources) {
				digest.update(read(resource).getBytes(StandardCharsets.UTF_8));
			}
			return HexFormat.of().formatHex(digest.digest()).substring(0, 8);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static String read(String resource) throws IOException {
		return new String(new ClassPathResource(resource).getContentAsByteArray(), StandardCharsets.UTF_8);
	}

	/** The client id is configuration, not user input, but it still goes into an HTML attribute. */
	private static String attribute(String value) {
		return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
