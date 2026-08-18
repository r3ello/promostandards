package com.trophy.promostandards.productdata.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.Product.ProductPart;
import com.trophy.promostandards.productdata.model.Product.RelatedProduct;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductCloseOutRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductDateModifiedRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductSellableRequest;
import com.trophy.promostandards.productdata.model.ProductDateModified;
import com.trophy.promostandards.productdata.model.ProductSellable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory stub for {@link ProductDataClient}. Active by default; deactivates when
 * {@code promostandards.product-data.mode=soap}.
 *
 * <p>The sellable catalog includes a small {@code TROPHY-*} family whose members link to each other
 * via {@code Common Grouping} related products, so the catalog "group variants" view has something to
 * collapse when running against the stub (no live supplier).
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.product-data", name = "mode", havingValue = "stub",
		matchIfMissing = true)
public class StubProductDataClient implements ProductDataClient {

	/** A single product sold under three separate ids (one per size), linked by Common Grouping. */
	private static final List<String> TROPHY_FAMILY = List.of("TROPHY-SM", "TROPHY-MD", "TROPHY-LG");

	@Override
	public Product getProduct(GetProductRequest request) {
		String productId = requireProductId(request.productId());
		if (TROPHY_FAMILY.contains(productId)) {
			return trophyMember(productId);
		}
		return new Product(productId, "Sample Polo Shirt",
				"Soft-touch piqué polo with embroidered logo area.", "Trophy Apparel",
				List.of("Apparel", "Polos"),
				List.of(
						new ProductPart(productId + "-RED", "Red colorway", "Red", List.of("S", "M", "L", "XL")),
						new ProductPart(productId + "-BLU", "Blue colorway", "Blue", List.of("S", "M", "L"))));
	}

	/** A trophy family member that Common-Groups the other two sizes. */
	private static Product trophyMember(String productId) {
		String size = switch (productId) {
			case "TROPHY-SM" -> "Small";
			case "TROPHY-MD" -> "Medium";
			default -> "Large";
		};
		List<RelatedProduct> siblings = new ArrayList<>();
		for (String other : TROPHY_FAMILY) {
			if (!other.equals(productId)) {
				siblings.add(new RelatedProduct("Common Grouping", other, null));
			}
		}
		return new Product(productId, "Classic Trophy Cup",
				"Gold-tone trophy cup on a marble base — sold per size.", "Trophy Awards",
				List.of("Awards", "Trophies"),
				List.of(new ProductPart(productId + "-GLD", size + " · Gold", "Gold", List.of(size))),
				siblings);
	}

	@Override
	public List<ProductSellable> getProductSellable(GetProductSellableRequest request) {
		boolean wantSellable = request.isSellable();
		if (request.productId() != null && !request.productId().isBlank()) {
			String productId = request.productId();
			return List.of(
					new ProductSellable(productId, productId + (wantSellable ? "-RED" : "-GRN"), wantSellable),
					new ProductSellable(productId, productId + (wantSellable ? "-BLU" : "-BLK"), wantSellable));
		}
		// Catalog discovery (no productId): the standalone SAMPLE-001 plus the linked TROPHY family.
		List<ProductSellable> all = new ArrayList<>();
		all.add(new ProductSellable("SAMPLE-001", wantSellable ? "SAMPLE-001-RED" : "SAMPLE-001-GRN", wantSellable));
		all.add(new ProductSellable("SAMPLE-001", wantSellable ? "SAMPLE-001-BLU" : "SAMPLE-001-BLK", wantSellable));
		for (String id : TROPHY_FAMILY) {
			all.add(new ProductSellable(id, id + "-GLD", wantSellable));
		}
		return all;
	}

	@Override
	public List<ProductCloseOut> getProductCloseOut(GetProductCloseOutRequest request) {
		// LEGACY-009 is outside the sellable catalog (a product dropped entirely); TROPHY-SM is inside
		// it, so the console's close-out badge is exercisable in stub mode too.
		return List.of(new ProductCloseOut("LEGACY-009", "LEGACY-009-GRN"),
				new ProductCloseOut("TROPHY-SM", "TROPHY-SM-GLD"));
	}

	@Override
	public List<ProductDateModified> getProductDateModified(GetProductDateModifiedRequest request) {
		return List.of(
				new ProductDateModified("SAMPLE-001", "SAMPLE-001-RED"),
				new ProductDateModified("SAMPLE-002", "SAMPLE-002-BLU"));
	}

	private static String requireProductId(String productId) {
		if (productId == null || productId.isBlank()) {
			throw new PromoStandardsClientException("productId is required",
					List.of(ServiceMessage.error(110, "Required field productId is missing")));
		}
		return productId;
	}
}
