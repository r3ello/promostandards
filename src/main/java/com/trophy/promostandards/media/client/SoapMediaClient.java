package com.trophy.promostandards.media.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.model.MediaDateModified;
import com.trophy.promostandards.media.model.MediaRequests;
import com.trophy.promostandards.media.soap.GetMediaContentRequest;
import com.trophy.promostandards.media.soap.GetMediaContentResponse;
import com.trophy.promostandards.media.soap.GetMediaDateModifiedRequest;
import com.trophy.promostandards.media.soap.GetMediaDateModifiedResponse;
import com.trophy.promostandards.media.soap.MediaContentService;
import com.trophy.promostandards.media.soap.shared.ErrorMessage;
import com.trophy.promostandards.media.soap.shared.MediaTypeType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.xml.datatype.DatatypeConfigurationException;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.function.Supplier;

/**
 * SOAP-backed {@link MediaClient} for the Media Content Service (1.1.0), implemented over the
 * CXF-generated JAX-WS port. Active when {@code promostandards.media.mode=soap}; the stub
 * ({@link StubMediaClient}) is active otherwise.
 *
 * <p>Maps the application's model records to/from the generated SOAP types. A 1.1.0 response carries
 * an {@code errorMessage} (code + description) on failure; its presence — or any transport/SOAP
 * fault — is surfaced as a {@link PromoStandardsClientException}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.media", name = "mode", havingValue = "soap")
public class SoapMediaClient implements MediaClient {

	private final MediaContentService port;

	public SoapMediaClient(MediaContentService port) {
		this.port = port;
	}

	@Override
	public List<MediaContent> getMediaContent(MediaRequests.GetMediaContentRequest request) {
		GetMediaContentRequest soapRequest = new GetMediaContentRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setProductId(request.productId());
		soapRequest.setMediaType(MediaTypeType.fromValue(request.mediaType())); // required by 1.1.0
		soapRequest.setClassType(request.classType());

		GetMediaContentResponse response = call(() -> port.getMediaContent(soapRequest), "getMediaContent");
		throwIfError(response.getErrorMessage());
		List<MediaContent> result = new ArrayList<>();
		if (response.getMediaContentArray() != null) {
			for (var item : response.getMediaContentArray().getMediaContent()) {
				result.add(toModel(item));
			}
		}
		return result;
	}

	@Override
	public List<MediaDateModified> getMediaDateModified(MediaRequests.GetMediaDateModifiedRequest request) {
		GetMediaDateModifiedRequest soapRequest = new GetMediaDateModifiedRequest();
		soapRequest.setWsVersion(request.wsVersion());
		soapRequest.setId(request.id());
		soapRequest.setPassword(request.password());
		soapRequest.setChangeTimeStamp(toXmlCalendar(request.changeTimeStamp()));

		GetMediaDateModifiedResponse response = call(() -> port.getMediaDateModified(soapRequest), "getMediaDateModified");
		throwIfError(response.getErrorMessage());
		List<MediaDateModified> result = new ArrayList<>();
		if (response.getMediaDateModifiedArray() != null) {
			for (var item : response.getMediaDateModifiedArray().getMediaDateModified()) {
				result.add(new MediaDateModified(item.getProductId(), item.getPartId()));
			}
		}
		return result;
	}

	// --- response mapping ------------------------------------------------------------------

	private static MediaContent toModel(com.trophy.promostandards.media.soap.MediaContent m) {
		Integer classTypeId = null;
		String classTypeName = null;
		if (m.getClassTypeArray() != null && !m.getClassTypeArray().getClassType().isEmpty()) {
			var classType = m.getClassTypeArray().getClassType().get(0);
			classTypeId = classType.getClassTypeId();
			classTypeName = classType.getClassTypeName();
		}
		return new MediaContent(
				m.getProductId(),
				m.getPartId(),
				m.getMediaType() != null ? m.getMediaType().value() : null,
				m.getUrl(),
				fileNameFrom(m.getUrl()),
				m.getDescription(),
				classTypeId,
				classTypeName,
				toInt(m.getWidth()),
				toInt(m.getHeight()));
	}

	private static Integer toInt(BigDecimal value) {
		return value == null ? null : value.intValue();
	}

	/** 1.1.0 has no fileName field; derive a best-effort name from the URL's last path segment. */
	private static String fileNameFrom(String url) {
		if (url == null || url.isBlank()) {
			return null;
		}
		String path = url;
		int query = path.indexOf('?');
		if (query >= 0) {
			path = path.substring(0, query);
		}
		int slash = path.lastIndexOf('/');
		String name = slash >= 0 ? path.substring(slash + 1) : path;
		return name.isBlank() ? null : name;
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
				"PromoStandards media service returned an error: " + errorMessage.getDescription(), List.of(message));
	}
}
