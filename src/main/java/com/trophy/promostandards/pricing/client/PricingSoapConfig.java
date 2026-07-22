package com.trophy.promostandards.pricing.client;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.pricing.soap.PricingAndConfigurationService;
import org.apache.cxf.ext.logging.LoggingFeature;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the CXF JAX-WS client proxy for the Pricing and Configuration Service. Only created when
 * {@code promostandards.pricing.mode=soap}; the proxy is configured from the generated SEI
 * annotations (no runtime WSDL fetch needed) with the endpoint, timeouts, and optional message
 * logging taken from {@link PromoStandardsProperties}.
 */
@Configuration
@ConditionalOnProperty(prefix = "promostandards.pricing", name = "mode", havingValue = "soap")
public class PricingSoapConfig {

	@Bean
	PricingAndConfigurationService pricingSoapPort(PromoStandardsProperties properties) {
		PromoStandardsProperties.Service cfg = properties.getPricing();
		if (cfg.getEndpointUrl() == null || cfg.getEndpointUrl().isBlank()) {
			throw new IllegalStateException(
					"promostandards.pricing.endpoint-url must be set when promostandards.pricing.mode=soap");
		}

		JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
		factory.setServiceClass(PricingAndConfigurationService.class);
		factory.setAddress(cfg.getEndpointUrl());
		if (cfg.isLogMessages()) {
			factory.getFeatures().add(new LoggingFeature());
		}

		PricingAndConfigurationService port = factory.create(PricingAndConfigurationService.class);

		HTTPConduit conduit = (HTTPConduit) ClientProxy.getClient(port).getConduit();
		HTTPClientPolicy policy = new HTTPClientPolicy();
		policy.setConnectionTimeout(cfg.getConnectTimeoutMs());
		policy.setReceiveTimeout(cfg.getReceiveTimeoutMs());
		conduit.setClient(policy);

		return port;
	}
}
