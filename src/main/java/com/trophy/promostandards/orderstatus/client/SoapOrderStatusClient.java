package com.trophy.promostandards.orderstatus.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.orderstatus.model.OrderStatus;
import com.trophy.promostandards.orderstatus.model.OrderStatus.OrderStatusDetail;
import com.trophy.promostandards.orderstatus.model.OrderStatusRequests;
import com.trophy.promostandards.orderstatus.model.OrderStatusType;
import com.trophy.promostandards.orderstatus.soap.GetOrderStatusDetailsRequest;
import com.trophy.promostandards.orderstatus.soap.GetOrderStatusDetailsResponse;
import com.trophy.promostandards.orderstatus.soap.GetOrderStatusTypesRequest;
import com.trophy.promostandards.orderstatus.soap.GetOrderStatusTypesResponse;
import com.trophy.promostandards.orderstatus.soap.OrderStatusService;
import jakarta.xml.bind.JAXBElement;
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
 * SOAP-backed {@link OrderStatusClient} for the Order Status Service (1.0.0). Active when
 * {@code promostandards.order-status.mode=soap}; the stub ({@link StubOrderStatusClient}) is active
 * otherwise.
 *
 * <p>Maps the application's model records to/from the generated SOAP types. The 1.0.0 schema reports
 * failures via a single {@code errorMessage} string; a non-blank value — or any transport/SOAP
 * fault — is surfaced as a {@link PromoStandardsClientException}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.order-status", name = "mode", havingValue = "soap")
public class SoapOrderStatusClient implements OrderStatusClient {

	private final OrderStatusService port;

	public SoapOrderStatusClient(OrderStatusService port) {
		this.port = port;
	}

	@Override
	public List<OrderStatus> getOrderStatusDetails(OrderStatusRequests.GetOrderStatusDetailsRequest request) {
		GetOrderStatusDetailsRequest soapRequest = new GetOrderStatusDetailsRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setQueryType(request.queryType());
		soapRequest.setReferenceNumber(request.referenceNumber());
		soapRequest.setStatusTimeStamp(toXmlCalendar(request.statusTimeStamp()));

		GetOrderStatusDetailsResponse response =
				call(() -> port.getOrderStatusDetails(soapRequest), "getOrderStatusDetails");
		throwIfError(response.getErrorMessage());

		List<OrderStatus> result = new ArrayList<>();
		if (response.getOrderStatusArray() != null) {
			for (var orderStatus : response.getOrderStatusArray().getOrderStatus()) {
				List<OrderStatusDetail> details = new ArrayList<>();
				if (orderStatus.getOrderStatusDetailArray() != null) {
					for (var detail : orderStatus.getOrderStatusDetailArray().getOrderStatusDetail()) {
						details.add(new OrderStatusDetail(
								detail.getFactoryOrderNumber(),
								detail.getStatusID() != null ? detail.getStatusID().intValue() : 0,
								detail.getStatusName(),
								toInstant(detail.getExpectedShipDate()),
								toInstant(detail.getExpectedDeliveryDate()),
								detail.getAdditionalExplanation(),
								Boolean.TRUE.equals(detail.isResponseRequired()),
								toInstant(detail.getValidTimestamp())));
					}
				}
				result.add(new OrderStatus(orderStatus.getPurchaseOrderNumber(), details));
			}
		}
		return result;
	}

	@Override
	public List<OrderStatusType> getOrderStatusTypes(OrderStatusRequests.GetOrderStatusTypesRequest request) {
		GetOrderStatusTypesRequest soapRequest = new GetOrderStatusTypesRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());

		GetOrderStatusTypesResponse response = call(() -> port.getOrderStatusTypes(soapRequest), "getOrderStatusTypes");
		throwIfError(response.getErrorMessage());

		List<OrderStatusType> result = new ArrayList<>();
		if (response.getStatusArray() != null) {
			for (var status : response.getStatusArray().getStatus()) {
				result.add(new OrderStatusType(status.getId(), status.getName()));
			}
		}
		return result;
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
			throw new PromoStandardsClientException("could not convert statusTimeStamp to XML calendar", ex);
		}
	}

	private static Instant toInstant(XMLGregorianCalendar calendar) {
		return calendar == null ? null : calendar.toGregorianCalendar().toInstant();
	}

	private static Instant toInstant(JAXBElement<XMLGregorianCalendar> element) {
		return element == null ? null : toInstant(element.getValue());
	}

	private static void throwIfError(String errorMessage) {
		if (errorMessage != null && !errorMessage.isBlank()) {
			throw new PromoStandardsClientException(
					"PromoStandards order status service returned an error: " + errorMessage);
		}
	}
}
