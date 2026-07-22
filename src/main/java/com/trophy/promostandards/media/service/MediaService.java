package com.trophy.promostandards.media.service;

import com.trophy.promostandards.config.PromoStandardsProperties;
import com.trophy.promostandards.media.client.MediaClient;
import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.model.MediaDateModified;
import com.trophy.promostandards.media.model.MediaRequests.GetMediaContentRequest;
import com.trophy.promostandards.media.model.MediaRequests.GetMediaDateModifiedRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Application service over the {@link MediaClient}. Applies configured credentials and
 * {@code wsVersion} before delegating to the client.
 */
@Service
public class MediaService {

	private final MediaClient client;
	private final PromoStandardsProperties properties;

	public MediaService(MediaClient client, PromoStandardsProperties properties) {
		this.client = client;
		this.properties = properties;
	}

	/** 1.1.0 requires a {@code mediaType}; default to {@code Image} (the product-gallery case). */
	private static final String DEFAULT_MEDIA_TYPE = "Image";

	public List<MediaContent> getMediaContent(String productId, String mediaType, Integer classType) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		String type = (mediaType == null || mediaType.isBlank()) ? DEFAULT_MEDIA_TYPE : mediaType;
		return client.getMediaContent(new GetMediaContentRequest(wsVersion(), creds.getId(), creds.getPassword(),
				productId, type, classType));
	}

	public List<MediaDateModified> getMediaDateModified(Instant changedSince) {
		PromoStandardsProperties.Credentials creds = properties.getCredentials();
		return client.getMediaDateModified(
				new GetMediaDateModifiedRequest(wsVersion(), creds.getId(), creds.getPassword(), changedSince));
	}

	private String wsVersion() {
		return properties.getMedia().getWsVersion();
	}
}
