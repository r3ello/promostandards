package com.trophy.promostandards.productdata.service;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.productdata.client.ProductDataClient;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductCloseOutRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductDateModifiedRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductSellableRequest;
import com.trophy.promostandards.productdata.model.ProductDateModified;
import com.trophy.promostandards.productdata.model.ProductSellable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Application service over the {@link ProductDataClient}. Applies configured credentials,
 * {@code wsVersion}, and localization defaults before delegating to the client.
 */
@Service
public class ProductDataService {

	private static final String DEFAULT_COUNTRY = "US";
	private static final String DEFAULT_LANGUAGE = "en";

	private final ProductDataClient client;
	private final PromoStandardsProperties properties;

	public ProductDataService(ProductDataClient client, PromoStandardsProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	public Product getProduct(String productId, String country, String language) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getProduct(new GetProductRequest(wsVersion(), creds.getId(), creds.getPassword(),
				country(country), language(language), productId));
	}

	public List<ProductSellable> getProductSellable(String productId, boolean isSellable) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getProductSellable(new GetProductSellableRequest(wsVersion(), creds.getId(), creds.getPassword(),
				productId, isSellable));
	}

	public List<ProductCloseOut> getProductCloseOut() {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getProductCloseOut(new GetProductCloseOutRequest(wsVersion(), creds.getId(), creds.getPassword()));
	}

	public List<ProductDateModified> getProductDateModified(Instant changedSince) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getProductDateModified(
				new GetProductDateModifiedRequest(wsVersion(), creds.getId(), creds.getPassword(), changedSince));
	}

	private String wsVersion() {
		return properties.getProductData().getWsVersion();
	}

	private static String country(String country) {
		return country == null || country.isBlank() ? DEFAULT_COUNTRY : country;
	}

	private static String language(String language) {
		return language == null || language.isBlank() ? DEFAULT_LANGUAGE : language;
	}
}
