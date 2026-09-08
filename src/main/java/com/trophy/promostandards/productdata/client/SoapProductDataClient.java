package com.trophy.promostandards.productdata.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.PromoStandardsNotFoundException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.productdata.model.Product;
import com.trophy.promostandards.productdata.model.ProductCloseOut;
import com.trophy.promostandards.productdata.model.ProductDataRequests;
import com.trophy.promostandards.productdata.model.ProductDateModified;
import com.trophy.promostandards.productdata.model.ProductSellable;
import com.trophy.promostandards.productdata.soap.GetProductCloseOutRequest;
import com.trophy.promostandards.productdata.soap.GetProductCloseOutResponse;
import com.trophy.promostandards.productdata.soap.GetProductDateModifiedRequest;
import com.trophy.promostandards.productdata.soap.GetProductDateModifiedResponse;
import com.trophy.promostandards.productdata.soap.GetProductRequest;
import com.trophy.promostandards.productdata.soap.GetProductResponse;
import com.trophy.promostandards.productdata.soap.GetProductSellableRequest;
import com.trophy.promostandards.productdata.soap.GetProductSellableResponse;
import com.trophy.promostandards.productdata.soap.ProductDataService;
import com.trophy.promostandards.productdata.soap.shared.ErrorMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.function.Supplier;

/**
 * SOAP-backed {@link ProductDataClient} for the Product Data Service (1.0.0), implemented over the
 * CXF-generated JAX-WS port. Active when {@code promostandards.product-data.mode=soap}; the stub
 * ({@link StubProductDataClient}) is active otherwise.
 *
 * <p>Maps the application's model records to/from the generated SOAP types. A 1.0.0 response carries
 * an {@code ErrorMessage} (code + description) on failure; its presence — or any transport/SOAP
 * fault — is surfaced as a {@link PromoStandardsClientException}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.product-data", name = "mode", havingValue = "soap")
public class SoapProductDataClient implements ProductDataClient {

	private final ProductDataService port;

	public SoapProductDataClient(ProductDataService port) {
		this.port = port;
	}

	@Override
	public Product getProduct(ProductDataRequests.GetProductRequest request) {
		GetProductRequest soapRequest = new GetProductRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setLocalizationCountry(request.localizationCountry());
		soapRequest.setLocalizationLanguage(request.localizationLanguage());
		soapRequest.setProductId(request.productId());

		GetProductResponse response = call(() -> port.getProduct(soapRequest), "getProduct");
		throwIfError(response.getErrorMessage());
		var product = response.getProduct();
		if (product == null) {
			// Not a failure: the supplier's sellable catalog is wider than its Product Data records,
			// so an id can be listed and still have no product here (PaceSetter: GI840). Typed so
			// aggregating callers can degrade instead of failing the whole read.
			throw new PromoStandardsNotFoundException(
					"getProduct returned no product for productId=" + request.productId());
		}
		return toModel(product);
	}

	@Override
	public List<ProductSellable> getProductSellable(ProductDataRequests.GetProductSellableRequest request) {
		GetProductSellableRequest soapRequest = new GetProductSellableRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setProductId(request.productId()); // null returns all sellable products
		soapRequest.setIsSellable(request.isSellable());

		GetProductSellableResponse response = call(() -> port.getProductSellable(soapRequest), "getProductSellable");
		throwIfError(response.getErrorMessage());
		List<ProductSellable> result = new ArrayList<>();
		if (response.getProductSellableArray() != null) {
			for (var item : response.getProductSellableArray().getProductSellable()) {
				result.add(new ProductSellable(item.getProductId(), item.getPartId(), request.isSellable()));
			}
		}
		return result;
	}

	@Override
	public List<ProductCloseOut> getProductCloseOut(ProductDataRequests.GetProductCloseOutRequest request) {
		GetProductCloseOutRequest soapRequest = new GetProductCloseOutRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());

		GetProductCloseOutResponse response = call(() -> port.getProductCloseOut(soapRequest), "getProductCloseOut");
		throwIfError(response.getErrorMessage());
		List<ProductCloseOut> result = new ArrayList<>();
		if (response.getProductCloseOutArray() != null) {
			for (var item : response.getProductCloseOutArray().getProductCloseOut()) {
				result.add(new ProductCloseOut(item.getProductId(), item.getPartId()));
			}
		}
		return result;
	}

	@Override
	public List<ProductDateModified> getProductDateModified(ProductDataRequests.GetProductDateModifiedRequest request) {
		GetProductDateModifiedRequest soapRequest = new GetProductDateModifiedRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setChangeTimeStamp(toXmlCalendar(request.changeTimeStamp()));

		GetProductDateModifiedResponse response =
				call(() -> port.getProductDateModified(soapRequest), "getProductDateModified");
		throwIfError(response.getErrorMessage());
		List<ProductDateModified> result = new ArrayList<>();
		if (response.getProductDateModifiedArray() != null) {
			for (var item : response.getProductDateModifiedArray().getProductDateModified()) {
				result.add(new ProductDateModified(item.getProductId(), item.getPartId()));
			}
		}
		return result;
	}

	// --- response mapping ------------------------------------------------------------------

	private static Product toModel(com.trophy.promostandards.productdata.soap.Product product) {
		List<String> categories = new ArrayList<>();
		if (product.getProductCategoryArray() != null) {
			for (var category : product.getProductCategoryArray().getProductCategory()) {
				if (category.getCategory() != null) {
					categories.add(category.getCategory());
				}
			}
		}

		List<Product.ProductPart> parts = new ArrayList<>();
		if (product.getProductPartArray() != null) {
			for (var part : product.getProductPartArray().getProductPart()) {
				String primaryColor = null;
				if (part.getColorArray() != null && !part.getColorArray().getColor().isEmpty()) {
					primaryColor = part.getColorArray().getColor().get(0).getColorName();
				}
				List<String> sizes = new ArrayList<>();
				var apparelSize = part.getApparelSize();
				if (apparelSize != null && apparelSize.getLabelSize() != null) {
					sizes.add(apparelSize.getLabelSize());
				}
				// The Dimension block is where the shipping weight lives; PaceSetter fills it for
				// every part (0.1 LB, 4 LB...) and it is the one figure the store cannot get anywhere
				// else — the dimensions beside it are the same numbers as the inventory size string.
				java.math.BigDecimal weight = null;
				String weightUom = null;
				var dimension = part.getDimension();
				if (dimension != null) {
					weight = dimension.getWeight();
					weightUom = dimension.getWeightUom() == null ? null : dimension.getWeightUom().value();
				}
				parts.add(new Product.ProductPart(part.getPartId(), joinText(part.getDescription()),
						primaryColor, sizes, weight, weightUom));
			}
		}

		// Related products (Substitute / Companion Sell / Common Grouping). The catalog group index
		// keeps only the Common Grouping links; we map them all and let callers filter.
		List<Product.RelatedProduct> relatedProducts = new ArrayList<>();
		if (product.getRelatedProductArray() != null) {
			for (var related : product.getRelatedProductArray().getRelatedProduct()) {
				String relationType = related.getRelationType() == null ? null : related.getRelationType().value();
				relatedProducts.add(new Product.RelatedProduct(
						relationType, related.getProductId(), related.getPartId()));
			}
		}

		return new Product(product.getProductId(), product.getProductName(), joinText(product.getDescription()),
				product.getProductBrand(), categories, parts, relatedProducts);
	}

	private static String joinText(List<String> values) {
		if (values == null || values.isEmpty()) {
			return null;
		}
		return String.join(" ", values);
	}

	// --- helpers ---------------------------------------------------------------------------

	private static <T> T call(Supplier<T> soapCall, String operation) {
		try {
			return soapCall.get();
		}
		catch (RuntimeException ex) {
			throw new PromoStandardsClientException(operation + " call failed: " + ex.getMessage(), ex);
		}
	}

	private static XMLGregorianCalendar toXmlCalendar(Instant instant) {
		if (instant == null) {
			return null;
		}
		try {
			GregorianCalendar calendar = GregorianCalendar.from(instant.atZone(ZoneOffset.UTC));
			return DatatypeFactory.newInstance().newXMLGregorianCalendar(calendar);
		}
		catch (DatatypeConfigurationException ex) {
			throw new PromoStandardsClientException("could not convert changeTimeStamp to XML calendar", ex);
		}
	}

	private static void throwIfError(ErrorMessage errorMessage) {
		if (errorMessage == null) {
			return;
		}
		ServiceMessage message = ServiceMessage.error(errorMessage.getCode(), errorMessage.getDescription());
		throw new PromoStandardsClientException(
				"PromoStandards product data service returned an error: " + errorMessage.getDescription(),
				List.of(message));
	}
}
