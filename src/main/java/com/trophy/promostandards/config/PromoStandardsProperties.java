package com.trophy.promostandards.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration for the PromoStandards integration layer, bound from the
 * {@code promostandards.*} namespace.
 *
 * <p>Credentials and endpoints are configured server-side (per supplier account) rather than
 * being passed in by REST callers. Each service has its own {@link Service} block so the
 * {@code mode} can be flipped from {@code stub} to {@code soap} independently as real SOAP
 * clients become available. See {@code README.md} for the swap procedure.
 */
@ConfigurationProperties(prefix = "promostandards")
public class PromoStandardsProperties {

	@NestedConfigurationProperty
	private Credentials credentials = new Credentials();

	@NestedConfigurationProperty
	private Service inventory = new Service();

	@NestedConfigurationProperty
	private Service productData = new Service();

	@NestedConfigurationProperty
	private Service pricing = new Service();

	@NestedConfigurationProperty
	private Service media = new Service();

	@NestedConfigurationProperty
	private Service orderShipment = new Service();

	@NestedConfigurationProperty
	private Service orderStatus = new Service();

	public Credentials getCredentials() {
		return credentials;
	}

	public void setCredentials(Credentials credentials) {
		this.credentials = credentials;
	}

	public Service getInventory() {
		return inventory;
	}

	public void setInventory(Service inventory) {
		this.inventory = inventory;
	}

	public Service getProductData() {
		return productData;
	}

	public void setProductData(Service productData) {
		this.productData = productData;
	}

	public Service getPricing() {
		return pricing;
	}

	public void setPricing(Service pricing) {
		this.pricing = pricing;
	}

	public Service getMedia() {
		return media;
	}

	public void setMedia(Service media) {
		this.media = media;
	}

	public Service getOrderShipment() {
		return orderShipment;
	}

	public void setOrderShipment(Service orderShipment) {
		this.orderShipment = orderShipment;
	}

	public Service getOrderStatus() {
		return orderStatus;
	}

	public void setOrderStatus(Service orderStatus) {
		this.orderStatus = orderStatus;
	}

	/** Supplier account credentials sent with every PromoStandards request. */
	public static class Credentials {

		/** PromoStandards account id (sent as {@code id} in requests). */
		private String id;

		/** PromoStandards account password. */
		private String password;

		public String getId() {
			return id;
		}

		public void setId(String id) {
			this.id = id;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}
	}

	/** Per-service settings. */
	public static class Service {

		/**
		 * Which client implementation to activate: {@code stub} (default) or {@code soap}.
		 * Stubs are wired with {@code @ConditionalOnProperty(... matchIfMissing = true)};
		 * a future SOAP client should activate on {@code soap}.
		 */
		private String mode = "stub";

		/** SOAP endpoint URL for this service (only used once a real SOAP client is wired). */
		private String endpointUrl;

		/** PromoStandards WSDL version sent as {@code wsVersion} (e.g. 1.2.1, 2.0.0). */
		private String wsVersion;

		/** SOAP connect timeout in milliseconds (only used by the SOAP client). */
		private long connectTimeoutMs = 10_000;

		/** SOAP receive/read timeout in milliseconds (only used by the SOAP client). */
		private long receiveTimeoutMs = 30_000;

		/** When true, log SOAP request/response messages (verbose; only used by the SOAP client). */
		private boolean logMessages = false;

		public String getMode() {
			return mode;
		}

		public void setMode(String mode) {
			this.mode = mode;
		}

		public String getEndpointUrl() {
			return endpointUrl;
		}

		public void setEndpointUrl(String endpointUrl) {
			this.endpointUrl = endpointUrl;
		}

		public String getWsVersion() {
			return wsVersion;
		}

		public void setWsVersion(String wsVersion) {
			this.wsVersion = wsVersion;
		}

		public long getConnectTimeoutMs() {
			return connectTimeoutMs;
		}

		public void setConnectTimeoutMs(long connectTimeoutMs) {
			this.connectTimeoutMs = connectTimeoutMs;
		}

		public long getReceiveTimeoutMs() {
			return receiveTimeoutMs;
		}

		public void setReceiveTimeoutMs(long receiveTimeoutMs) {
			this.receiveTimeoutMs = receiveTimeoutMs;
		}

		public boolean isLogMessages() {
			return logMessages;
		}

		public void setLogMessages(boolean logMessages) {
			this.logMessages = logMessages;
		}
	}
}
