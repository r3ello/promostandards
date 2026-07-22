package com.trophy.promostandards.productdata.client;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.productdata.soap.ProductDataService;
import org.apache.cxf.ext.logging.LoggingFeature;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the CXF JAX-WS client proxy for the Product Data Service. Only created when
 * {@code promostandards.product-data.mode=soap}; the proxy is configured from the generated SEI
 * annotations (no runtime WSDL fetch needed) with the endpoint, timeouts, and optional message
 * logging taken from {@link PromoStandardsProperties}.
 */
@Configuration
@ConditionalOnProperty(prefix = "promostandards.product-data", name = "mode", havingValue = "soap")
public class ProductDataSoapConfig {

	@Bean
	ProductDataService productDataSoapPort(PromoStandardsProperties properties) {
		PromoStandardsProperties.Service cfg = properties.getProductData();
		if (cfg.getEndpointUrl() == null || cfg.getEndpointUrl().isBlank()) {
			throw new IllegalStateException(
					"promostandards.product-data.endpoint-url must be set when promostandards.product-data.mode=soap");
		}

		JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
		factory.setServiceClass(ProductDataService.class);
		factory.setAddress(cfg.getEndpointUrl());
		if (cfg.isLogMessages()) {
			factory.getFeatures().add(new LoggingFeature());
		}

		ProductDataService port = factory.create(ProductDataService.class);

		HTTPConduit conduit = (HTTPConduit) ClientProxy.getClient(port).getConduit();
		HTTPClientPolicy policy = new HTTPClientPolicy();
		policy.setConnectionTimeout(cfg.getConnectTimeoutMs());
		policy.setReceiveTimeout(cfg.getReceiveTimeoutMs());
		conduit.setClient(policy);

		return port;
	}
}
