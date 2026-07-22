package com.trophy.promostandards.productdata.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.Product.ProductPart;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductCloseOutRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductDateModifiedRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductSellableRequest;
import com.trophy.promostandards.productdata.model.ProductDateModified;
import com.trophy.promostandards.productdata.model.ProductSellable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-memory stub for {@link ProductDataClient}. Active by default; deactivates when
 * {@code promostandards.product-data.mode=soap}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.product-data", name = "mode", havingValue = "stub",
		matchIfMissing = true)
public class StubProductDataClient implements ProductDataClient {

	@Override
	public Product getProduct(GetProductRequest request) {
		String productId = requireProductId(request.productId());
		return new Product(productId, "Sample Polo Shirt",
				"Soft-touch piqué polo with embroidered logo area.", "Trophy Apparel",
				List.of("Apparel", "Polos"),
				List.of(
						new ProductPart(productId + "-RED", "Red colorway", "Red", List.of("S", "M", "L", "XL")),
						new ProductPart(productId + "-BLU", "Blue colorway", "Blue", List.of("S", "M", "L"))));
	}

	@Override
	public List<ProductSellable> getProductSellable(GetProductSellableRequest request) {
		String productId = request.productId() == null || request.productId().isBlank()
				? "SAMPLE-001" : request.productId();
		// 1.0.0 returns only the items matching the requested isSellable filter.
		boolean wantSellable = request.isSellable();
		return List.of(
				new ProductSellable(productId, productId + (wantSellable ? "-RED" : "-GRN"), wantSellable),
				new ProductSellable(productId, productId + (wantSellable ? "-BLU" : "-BLK"), wantSellable));
	}

	@Override
	public List<ProductCloseOut> getProductCloseOut(GetProductCloseOutRequest request) {
		return List.of(new ProductCloseOut("LEGACY-009", "LEGACY-009-GRN"));
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
