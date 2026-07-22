package com.trophy.promostandards.media.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.common.ServiceMessage;
import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.model.MediaDateModified;
import com.trophy.promostandards.media.model.MediaRequests.GetMediaContentRequest;
import com.trophy.promostandards.media.model.MediaRequests.GetMediaDateModifiedRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * In-memory stub for {@link MediaClient}. Active by default; deactivates when
 * {@code promostandards.media.mode=soap}.
 */
@Component
@ConditionalOnProperty(prefix = "promostandards.media", name = "mode", havingValue = "stub",
		matchIfMissing = true)
public class StubMediaClient implements MediaClient {

	@Override
	public List<MediaContent> getMediaContent(GetMediaContentRequest request) {
		String productId = requireProductId(request.productId());
		List<MediaContent> all = List.of(
				new MediaContent(productId, productId + "-RED", "Image",
						"https://cdn.example.com/" + productId + "/red-front.jpg", "red-front.jpg",
						"Red, front view", 1, "Front", 2000, 2000),
				new MediaContent(productId, productId + "-BLU", "Image",
						"https://cdn.example.com/" + productId + "/blue-front.jpg", "blue-front.jpg",
						"Blue, front view", 1, "Front", 2000, 2000),
				new MediaContent(productId, null, "Document",
						"https://cdn.example.com/" + productId + "/spec-sheet.pdf", "spec-sheet.pdf",
						"Product spec sheet", 9, "Document", null, null));
		String mediaType = request.mediaType();
		Integer classType = request.classType();
		return all.stream()
				.filter(m -> mediaType == null || mediaType.isBlank() || mediaType.equalsIgnoreCase(m.mediaType()))
				.filter(m -> classType == null || classType.equals(m.classTypeId()))
				.toList();
	}

	@Override
	public List<MediaDateModified> getMediaDateModified(GetMediaDateModifiedRequest request) {
		return List.of(
				new MediaDateModified("SAMPLE-001", "SAMPLE-001-RED"),
				new MediaDateModified("SAMPLE-003", "SAMPLE-003-BLU"));
	}

	private static String requireProductId(String productId) {
		if (productId == null || productId.isBlank()) {
			throw new PromoStandardsClientException("productId is required",
					List.of(ServiceMessage.error(110, "Required field productId is missing")));
		}
		return productId;
	}
}
