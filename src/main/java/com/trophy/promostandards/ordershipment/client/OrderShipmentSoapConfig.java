package com.trophy.promostandards.ordershipment.client;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.ordershipment.soap.OrderShipmentNotificationService;
import org.apache.cxf.ext.logging.LoggingFeature;
import org.apache.cxf.frontend.ClientProxy;
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean;
import org.apache.cxf.transport.http.HTTPConduit;
import org.apache.cxf.transports.http.configuration.HTTPClientPolicy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the CXF JAX-WS client proxy for the Order Shipment Notification Service. Only created when
 * {@code promostandards.order-shipment.mode=soap}; the endpoint, timeouts, and optional message
 * logging are taken from {@link PromoStandardsProperties}.
 */
@Configuration
@ConditionalOnProperty(prefix = "promostandards.order-shipment", name = "mode", havingValue = "soap")
public class OrderShipmentSoapConfig {

	@Bean
	OrderShipmentNotificationService orderShipmentSoapPort(PromoStandardsProperties properties) {
		PromoStandardsProperties.Service cfg = properties.getOrderShipment();
		if (cfg.getEndpointUrl() == null || cfg.getEndpointUrl().isBlank()) {
			throw new IllegalStateException(
					"promostandards.order-shipment.endpoint-url must be set when promostandards.order-shipment.mode=soap");
		}

		JaxWsProxyFactoryBean factory = new JaxWsProxyFactoryBean();
		factory.setServiceClass(OrderShipmentNotificationService.class);
		factory.setAddress(cfg.getEndpointUrl());
		if (cfg.isLogMessages()) {
			factory.getFeatures().add(new LoggingFeature());
		}

		OrderShipmentNotificationService port = factory.create(OrderShipmentNotificationService.class);

		HTTPConduit conduit = (HTTPConduit) ClientProxy.getClient(port).getConduit();
		HTTPClientPolicy policy = new HTTPClientPolicy();
		policy.setConnectionTimeout(cfg.getConnectTimeoutMs());
		policy.setReceiveTimeout(cfg.getReceiveTimeoutMs());
		conduit.setClient(policy);

		return port;
	}
}
