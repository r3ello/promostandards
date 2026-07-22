package com.trophy.promostandards.orderstatus.client;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.orderstatus.soap.OrderStatusService;
import org.apache.cxf.ext.logging.LoggingFeature;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the CXF JAX-WS client proxy for the Order Status Service. Only created when
 * {@code promostandards.order-status.mode=soap}; the endpoint, timeouts, and optional message
 * logging are taken from {@link PromoStandardsProperties}.
 */
@Configuration
@ConditionalOnProperty(prefix = "promostandards.order-status", name = "mode", havingValue = "soap")
public class OrderStatusSoapConfig {

	@Bean
	OrderStatusService orderStatusSoapPort(PromoStandardsProperties properties) {
		PromoStandardsProperties.Service cfg = properties.getOrderStatus();
		if (cfg.getEndpointUrl() == null || cfg.getEndpointUrl().isBlank()) {
			throw new IllegalStateException(
					"promostandards.order-status.endpoint-url must be set when promostandards.order-status.mode=soap");
		}

		JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
		factory.setServiceClass(OrderStatusService.class);
		factory.setAddress(cfg.getEndpointUrl());
		if (cfg.isLogMessages()) {
			factory.getFeatures().add(new LoggingFeature());
		}

		OrderStatusService port = factory.create(OrderStatusService.class);

		HTTPConduit conduit = (HTTPConduit) ClientProxy.getClient(port).getConduit();
		HTTPClientPolicy policy = new HTTPClientPolicy();
		policy.setConnectionTimeout(cfg.getConnectTimeoutMs());
		policy.setReceiveTimeout(cfg.getReceiveTimeoutMs());
		conduit.setClient(policy);

		return port;
	}
}
