package com.trophy.promostandards.inventory.client;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.inventory.soap.InventoryService;
import org.apache.cxf.ext.logging.LoggingFeature;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the CXF JAX-WS client proxy for the Inventory Service. Only created when
 * {@code promostandards.inventory.mode=soap}; the proxy is configured from the generated SEI
 * annotations (no runtime WSDL fetch needed) with the endpoint, timeouts, and optional message
 * logging taken from {@link PromoStandardsProperties}.
 */
@Configuration
@ConditionalOnProperty(prefix = "promostandards.inventory", name = "mode", havingValue = "soap")
public class InventorySoapConfig {

	@Bean
	InventoryService inventorySoapPort(PromoStandardsProperties properties) {
		PromoStandardsProperties.Service cfg = properties.getInventory();
		if (cfg.getEndpointUrl() == null || cfg.getEndpointUrl().isBlank()) {
			throw new IllegalStateException(
					"promostandards.inventory.endpoint-url must be set when promostandards.inventory.mode=soap");
		}

		JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
		factory.setServiceClass(InventoryService.class);
		factory.setAddress(cfg.getEndpointUrl());
		if (cfg.isLogMessages()) {
			factory.getFeatures().add(new LoggingFeature());
		}

		InventoryService port = factory.create(InventoryService.class);

		HTTPConduit conduit = (HTTPConduit) ClientProxy.getClient(port).getConduit();
		HTTPClientPolicy policy = new HTTPClientPolicy();
		policy.setConnectionTimeout(cfg.getConnectTimeoutMs());
		policy.setReceiveTimeout(cfg.getReceiveTimeoutMs());
		conduit.setClient(policy);

		return port;
	}
}
