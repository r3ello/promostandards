package com.trophy.promostandards.media.model;

import java.time.Instant;

/**
 * Request payloads for the Media Content Service (1.1.0). Credentials and {@code wsVersion} are
 * supplied by the service layer from configuration.
 */
public final class MediaRequests {

	private MediaRequests() {
	}

	/**
	 * Mirrors {@code GetMediaContentRequest}.
	 *
	 * @param mediaType optional filter: "Image", "Audio", "Video", or "Document" (null = all)
	 * @param classType optional PromoStandards class type id filter (null = all)
	 */
	public record GetMediaContentRequest(String wsVersion, String id, String password, String productId,
			String mediaType, Integer classType) {
	}

	/** Mirrors {@code GetMediaDateModifiedRequest}. */
	public record GetMediaDateModifiedRequest(String wsVersion, String id, String password, Instant changeTimeStamp) {
	}
}
