package com.trophy.promostandards.productdata.web;

import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductDateModified;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.service.ProductDataService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST facade for the PromoStandards Product Data Service.
 */
@RestController
@RequestMapping("/api/products")
public class ProductDataController {

	private final ProductDataService service;

	public ProductDataController(ProductDataService service) {
		this.service = service;
	}

	/** {@code getProduct} — full product detail. */
	@GetMapping("/{productId}")
	public Product getProduct(@PathVariable String productId,
			@RequestParam(required = false) String country,
			@RequestParam(required = false) String language) {
		return service.getProduct(productId, country, language);
	}

	/**
	 * {@code getProductSellable} — sellable products/parts. With no {@code productId} this lists
	 * every sellable product for the account (use it to discover product ids); with a
	 * {@code productId} it returns that product's sellable parts. {@code isSellable} defaults to true.
	 */
	@GetMapping("/sellable")
	public List<ProductSellable> getProductSellable(@RequestParam(required = false) String productId,
			@RequestParam(defaultValue = "true") boolean isSellable) {
		return service.getProductSellable(productId, isSellable);
	}

	/** {@code getProductCloseOut} — all close-out products/parts. */
	@GetMapping("/close-out")
	public List<ProductCloseOut> getProductCloseOut() {
		return service.getProductCloseOut();
	}

	/** {@code getProductDateModified} — products changed since {@code changedSince} (ISO-8601 instant). */
	@GetMapping("/date-modified")
	public List<ProductDateModified> getProductDateModified(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant changedSince) {
		return service.getProductDateModified(changedSince);
	}
}
