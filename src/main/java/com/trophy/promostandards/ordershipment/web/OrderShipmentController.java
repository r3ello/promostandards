package com.trophy.promostandards.ordershipment.web;

import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.service.OrderShipmentService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST facade for the PromoStandards Order Shipment Notification Service.
 */
@RestController
@RequestMapping("/api/order-shipments")
public class OrderShipmentController {

	private final OrderShipmentService service;

	public OrderShipmentController(OrderShipmentService service) {
		this.service = service;
	}

	/**
	 * {@code getOrderShipmentNotification} — supply either {@code poNumber} (shipments for a purchase
	 * order) or {@code since} (an ISO-8601 instant; shipments on/after that time).
	 */
	@GetMapping
	public List<OrderShipment> getOrderShipments(@RequestParam(required = false) String poNumber,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
		if (poNumber != null && !poNumber.isBlank()) {
			return service.getByPurchaseOrder(poNumber);
		}
		if (since != null) {
			return service.getShippedSince(since);
		}
		throw new IllegalArgumentException("provide either 'poNumber' or 'since'");
	}
}
