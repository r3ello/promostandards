package com.trophy.promostandards.ordershipment.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.ordershipment.model.OrderShipment;
import com.trophy.promostandards.ordershipment.model.OrderShipment.ShipmentItem;
import com.trophy.promostandards.ordershipment.model.OrderShipment.ShipmentPackage;
import com.trophy.promostandards.ordershipment.model.OrderShipmentRequests;
import com.trophy.promostandards.ordershipment.soap.GetOrderShipmentNotificationRequest;
import com.trophy.promostandards.ordershipment.soap.GetOrderShipmentNotificationResponse;
import com.trophy.promostandards.ordershipment.soap.OrderShipmentNotificationService;
import com.trophy.promostandards.ordershipment.soap.shared.ErrorMessage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.function.Supplier;

/**
 * SOAP-backed {@link OrderShipmentClient} for the Order Shipment Notification Service (1.0.0).
 * Active when {@code promostandards.order-shipment.mode=soap}; the stub
 * ({@link StubOrderShipmentClient}) is active otherwise.
 *
 * <p>Flattens the nested {@code OrderShipmentNotification → SalesOrder → ShipmentLocation → Package}
 * structure into the model's per-package list. A {@code errorMessage} (code + description) — or any
 * transport/SOAP fault — is surfaced as a {@link PromoStandardsClientException}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.order-shipment", name = "mode", havingValue = "soap")
public class SoapOrderShipmentClient implements OrderShipmentClient {

	private final OrderShipmentNotificationService port;

	public SoapOrderShipmentClient(OrderShipmentNotificationService port) {
		this.port = port;
	}

	@Override
	public List<OrderShipment> getOrderShipmentNotification(
			OrderShipmentRequests.GetOrderShipmentNotificationRequest request) {
		GetOrderShipmentNotificationRequest soapRequest = new GetOrderShipmentNotificationRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setQueryType(BigInteger.valueOf(request.queryType()));
		soapRequest.setReferenceNumber(request.referenceNumber());
		soapRequest.setShipmentDateTimeStamp(toXmlCalendar(request.shipmentDateTimeStamp()));

		GetOrderShipmentNotificationResponse response =
				call(() -> port.getOrderShipmentNotification(soapRequest), "getOrderShipmentNotification");
		throwIfError(response.getErrorMessage());

		List<OrderShipment> result = new ArrayList<>();
		if (response.getOrderShipmentNotificationArray() != null) {
			for (var osn : response.getOrderShipmentNotificationArray().getOrderShipmentNotification()) {
				List<ShipmentPackage> packages = new ArrayList<>();
				if (osn.getSalesOrderArray() != null) {
					for (var salesOrder : osn.getSalesOrderArray().getSalesOrder()) {
						if (salesOrder.getShipmentLocationArray() == null) {
							continue;
						}
						for (var location : salesOrder.getShipmentLocationArray().getShipmentLocation()) {
							var shipTo = location.getShipToAddress();
							if (location.getPackageArray() == null) {
								continue;
							}
							for (var pkg : location.getPackageArray().getPackage()) {
								List<ShipmentItem> items = new ArrayList<>();
								if (pkg.getItemArray() != null) {
									for (var item : pkg.getItemArray().getItem()) {
										items.add(new ShipmentItem(item.getSupplierProductId(), item.getSupplierPartId(),
												item.getQuantity()));
									}
								}
								packages.add(new ShipmentPackage(
										salesOrder.getSalesOrderNumber(),
										pkg.getTrackingNumber(),
										pkg.getCarrier(),
										pkg.getShipmentMethod(),
										toInstant(pkg.getShipmentDate()),
										shipTo != null ? shipTo.getCity() : null,
										shipTo != null ? shipTo.getRegion() : null,
										shipTo != null ? shipTo.getPostalCode() : null,
										shipTo != null ? shipTo.getCountry() : null,
										items));
							}
						}
					}
				}
				result.add(new OrderShipment(osn.getPurchaseOrderNumber(), osn.isComplete(), packages));
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
			throw new PromoStandardsClientException("could not convert shipmentDateTimeStamp to XML calendar", ex);
		}
	}

	private static Instant toInstant(XMLGregorianCalendar calendar) {
		return calendar == null ? null : calendar.toGregorianCalendar().toInstant();
	}

	private static void throwIfError(ErrorMessage errorMessage) {
		if (errorMessage == null) {
			return;
		}
		ServiceMessage message = ServiceMessage.error(errorMessage.getCode(), errorMessage.getDescription());
		throw new PromoStandardsClientException(
				"PromoStandards order shipment service returned an error: " + errorMessage.getDescription(),
				List.of(message));
	}
}
