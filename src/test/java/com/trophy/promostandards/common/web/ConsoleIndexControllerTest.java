package com.trophy.promostandards.common.web;

import com.trophy.promostandards.security.ShopifyEmbedProperties;
import com.trophy.promostandards.security.ShopifySessionToken;
import com.trophy.promostandards.shopify.ShopifyProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ConsoleIndexController}: the shell is served with App Bridge wired in when
 * the app is embedded and untouched when it is not — and, either way, never with the raw marker,
 * which would leave the console unable to authenticate inside the admin.
 */
class ConsoleIndexControllerTest {

	private static final String CLIENT_ID = "0123456789abcdef";

	private static String shell(boolean embedded) throws IOException {
		ShopifyProperties shopify = new ShopifyProperties("trophypartner.myshopify.com", CLIENT_ID,
				"secret", null, "2026-04", null);
		ConsoleIndexController controller = new ConsoleIndexController(
				new ShopifySessionToken(shopify, new ShopifyEmbedProperties(embedded)));
		return controller.index().getBody();
	}

	@Test
	void injectsAppBridgeAndTheApiKeyWhenEmbedded() throws IOException {
		String html = shell(true);

		assertThat(html).contains("<meta name=\"shopify-api-key\" content=\"" + CLIENT_ID + "\">")
				.contains("https://cdn.shopify.com/shopifycloud/app-bridge.js")
				.doesNotContain("<!--SHOPIFY_APP_BRIDGE-->");
		// App Bridge has to be the first script in the document, ahead of the console's own.
		assertThat(html.indexOf("app-bridge.js")).isLessThan(html.indexOf("app.js"));
	}

	@Test
	void servesThePlainShellWhenNotEmbedded() throws IOException {
		String html = shell(false);

		assertThat(html).doesNotContain("app-bridge").doesNotContain("shopify-api-key")
				.doesNotContain("<!--SHOPIFY_APP_BRIDGE-->")
				.contains("id=\"loginForm\"");
	}
}
