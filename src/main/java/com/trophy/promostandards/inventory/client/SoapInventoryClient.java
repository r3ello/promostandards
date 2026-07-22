package com.trophy.promostandards.inventory.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.inventory.model.FilterValues;
import com.trophy.promostandards.inventory.model.InventoryLevels;
import com.trophy.promostandards.inventory.model.InventoryRequests;
import com.trophy.promostandards.inventory.soap.GetFilterValuesReply;
import com.trophy.promostandards.inventory.soap.GetFilterValuesRequest;
import com.trophy.promostandards.inventory.soap.InventoryService;
import com.trophy.promostandards.inventory.soap.Reply;
import com.trophy.promostandards.inventory.soap.Request;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * SOAP-backed {@link InventoryClient} for the Inventory Service (1.2.1), implemented over the
 * CXF-generated JAX-WS port. Active when {@code promostandards.inventory.mode=soap}; the stub
 * ({@link StubInventoryClient}) is active otherwise.
 *
 * <p>Maps the application's model records to/from the generated SOAP types. The 1.2.1 schema
 * reports failures via a single {@code errorMessage} string (there is no {@code ServiceMessageArray}
 * as in 2.0.0); a non-blank {@code errorMessage} — or any transport/SOAP fault — is surfaced as a
 * {@link PromoStandardsClientException}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.inventory", name = "mode", havingValue = "soap")
public class SoapInventoryClient implements InventoryClient {

	/**
	 * 1.2.1 requires {@code productIDtype}; supplier product ids are the only id type these calls
	 * use, so it is fixed to {@code Supplier}.
	 */
	private static final String PRODUCT_ID_TYPE = "Supplier";

	private final InventoryService port;

	public SoapInventoryClient(InventoryService port) {
		this.port = port;
	}

	@Override
	public InventoryLevels getInventoryLevels(InventoryRequests.GetInventoryLevelsRequest request) {
		Request soapRequest = new Request();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setProductID(request.productId());
		soapRequest.setProductIDtype(PRODUCT_ID_TYPE);
		applyFilter(soapRequest, request.filter());

		Reply reply;
		try {
			reply = port.getInventoryLevels(soapRequest);
		}
		catch (RuntimeException ex) {
			throw new PromoStandardsClientException("getInventoryLevels call failed: " + ex.getMessage(), ex);
		}

		throwIfError(reply.getErrorMessage());
		return toModel(reply);
	}

	@Override
	public FilterValues getFilterValues(InventoryRequests.GetFilterValuesRequest request) {
		GetFilterValuesRequest soapRequest = new GetFilterValuesRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setProductID(request.productId());
		soapRequest.setProductIDtype(PRODUCT_ID_TYPE);

		GetFilterValuesReply reply;
		try {
			reply = port.getFilterValues(soapRequest);
		}
		catch (RuntimeException ex) {
			throw new PromoStandardsClientException("getFilterValues call failed: " + ex.getMessage(), ex);
		}

		throwIfError(reply.getErrorMessage());
		return new FilterValues(reply.getProductID(),
				reply.getFilterColorArray() != null ? List.copyOf(reply.getFilterColorArray().getFilterColor()) : List.of(),
				reply.getFilterSizeArray() != null ? List.copyOf(reply.getFilterSizeArray().getFilterSize()) : List.of(),
				reply.getFilterSelectionArray() != null
						? List.copyOf(reply.getFilterSelectionArray().getFilterSelection()) : List.of());
	}

	// --- request mapping -------------------------------------------------------------------

	private static void applyFilter(Request soapRequest, InventoryRequests.Filter filter) {
		if (filter == null) {
			return;
		}
		if (filter.colors() != null && !filter.colors().isEmpty()) {
			Request.FilterColorArray array = new Request.FilterColorArray();
			array.getFilterColor().addAll(filter.colors());
			soapRequest.setFilterColorArray(array);
		}
		if (filter.sizes() != null && !filter.sizes().isEmpty()) {
			Request.FilterSizeArray array = new Request.FilterSizeArray();
			array.getFilterSize().addAll(filter.sizes());
			soapRequest.setFilterSizeArray(array);
		}
		if (filter.selections() != null && !filter.selections().isEmpty()) {
			Request.FilterSelectionArray array = new Request.FilterSelectionArray();
			array.getFilterSelection().addAll(filter.selections());
			soapRequest.setFilterSelectionArray(array);
		}
	}

	// --- response mapping ------------------------------------------------------------------

	private static InventoryLevels toModel(Reply reply) {
		List<InventoryLevels.PartInventory> parts = new ArrayList<>();
		Reply.ProductVariationInventoryArray variationArray = reply.getProductVariationInventoryArray();
		if (variationArray != null) {
			for (Reply.ProductVariationInventoryArray.ProductVariationInventory variation
					: variationArray.getProductVariationInventory()) {
				parts.add(new InventoryLevels.PartInventory(
						unquote(variation.getPartID()),
						variation.getPartDescription(),
						variation.getPartBrand(),
						parseQuantity(variation.getQuantityAvailable()),
						variation.getAttributeColor(),
						variation.getAttributeSize(),
						variation.getAttributeSelection(),
						variation.getEntryType()));
			}
		}
		return new InventoryLevels(unquote(reply.getProductID()), parts);
	}

	/**
	 * Some suppliers (e.g. PaceSetter) echo identifiers wrapped in literal double-quotes
	 * (e.g. {@code "C0501A"}); strip a single surrounding pair so ids stay clean for joins/URLs.
	 */
	private static String unquote(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
			return trimmed.substring(1, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	private static Integer parseQuantity(String quantityAvailable) {
		if (quantityAvailable == null || quantityAvailable.isBlank()) {
			return null;
		}
		try {
			return Integer.valueOf(quantityAvailable.trim());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	// --- error handling --------------------------------------------------------------------

	private static void throwIfError(String errorMessage) {
		if (errorMessage != null && !errorMessage.isBlank()) {
			throw new PromoStandardsClientException(
					"PromoStandards inventory service returned an error: " + errorMessage);
		}
	}
}
