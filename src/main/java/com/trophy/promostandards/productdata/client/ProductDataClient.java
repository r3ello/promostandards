package com.trophy.promostandards.productdata.client;

import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductCloseOutRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductDateModifiedRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductRequest;
import com.trophy.promostandards.productdata.model.ProductDataRequests.GetProductSellableRequest;
import com.trophy.promostandards.productdata.model.ProductDateModified;
import com.trophy.promostandards.productdata.model.ProductSellable;

import java.util.List;

/**
 * Client for the PromoStandards Product Data Service (1.0.0).
 *
 * <p>Mirrors the SOAP operations so {@link StubProductDataClient} can be swapped for the real
 * SOAP-backed {@link SoapProductDataClient} via {@code promostandards.product-data.mode=soap}.
 */
public interface ProductDataClient {

	/** {@code getProduct} — full product detail. */
	Product getProduct(GetProductRequest request);

	/** {@code getProductSellable} — sellable flags per part. */
	List<ProductSellable> getProductSellable(GetProductSellableRequest request);

	/** {@code getProductCloseOut} — products/parts flagged as close-out. */
	List<ProductCloseOut> getProductCloseOut(GetProductCloseOutRequest request);

	/** {@code getProductDateModified} — products changed since the requested timestamp. */
	List<ProductDateModified> getProductDateModified(GetProductDateModifiedRequest request);
}
