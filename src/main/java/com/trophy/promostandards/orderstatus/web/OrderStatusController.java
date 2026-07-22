package com.trophy.promostandards.orderstatus.web;

import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.model.OrderStatusType;
import com.trophy.promostandards.orderstatus.service.OrderStatusService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST facade for the PromoStandards Order Status Service.
 */
@RestController
@RequestMapping("/api/order-status")
public class OrderStatusController {

	private final OrderStatusService service;

	public OrderStatusController(OrderStatusService service) {
		this.service = service;
	}

	/**
	 * {@code getOrderStatusDetails} — supply either {@code poNumber} (status for a purchase order)
	 * or {@code since} (an ISO-8601 instant; orders whose status changed on/after that time).
	 */
	@GetMapping
	public List<OrderStatus> getOrderStatusDetails(@RequestParam(required = false) String poNumber,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
		if (poNumber != null && !poNumber.isBlank()) {
			return service.getByPurchaseOrder(poNumber);
		}
		if (since != null) {
			return service.getUpdatedSince(since);
		}
		throw new IllegalArgumentException("provide either 'poNumber' or 'since'");
	}

	/** {@code getOrderStatusTypes} — the supplier's supported order-status codes. */
	@GetMapping("/types")
	public List<OrderStatusType> getOrderStatusTypes() {
		return service.getStatusTypes();
	}
}
