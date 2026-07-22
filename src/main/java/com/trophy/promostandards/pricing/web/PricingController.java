package com.trophy.promostandards.pricing.web;

import com.trophy.promostandards.pricing.model.Charge;
import com.trophy.promostandards.pricing.model.Configuration;
import com.trophy.promostandards.pricing.model.FobPoint;
import com.trophy.promostandards.pricing.service.PricingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST facade for the PromoStandards Pricing and Configuration Service.
 */
@RestController
@RequestMapping("/api/pricing")
public class PricingController {

	private final PricingService service;

	public PricingController(PricingService service) {
		this.service = service;
	}

	/** {@code getConfigurationAndPricing} — price breaks for a product's parts. */
	@GetMapping("/{productId}/configuration")
	public Configuration getConfigurationAndPricing(@PathVariable String productId,
			@RequestParam(required = false) String currency,
			@RequestParam(required = false) String fobId,
			@RequestParam(required = false, defaultValue = "Net") String priceType,
			@RequestParam(required = false, defaultValue = "Blank") String configurationType,
			@RequestParam(required = false) String country,
			@RequestParam(required = false) String language) {
		return service.getConfigurationAndPricing(productId, currency, fobId, priceType, configurationType, country,
				language);
	}

	/** {@code getAvailableCharges} — charges for a product. */
	@GetMapping("/{productId}/charges")
	public List<Charge> getAvailableCharges(@PathVariable String productId,
			@RequestParam(required = false) String country,
			@RequestParam(required = false) String language) {
		return service.getAvailableCharges(productId, country, language);
	}

	/** {@code getFobPoints} — FOB shipping origins for a product. */
	@GetMapping("/{productId}/fob-points")
	public List<FobPoint> getFobPoints(@PathVariable String productId,
			@RequestParam(required = false) String country,
			@RequestParam(required = false) String language) {
		return service.getFobPoints(productId, country, language);
	}
}
