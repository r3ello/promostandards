package com.trophy.promostandards.media.client;

import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.model.MediaDateModified;
import com.trophy.promostandards.media.model.MediaRequests.GetMediaContentRequest;
import com.trophy.promostandards.media.model.MediaRequests.GetMediaDateModifiedRequest;

import java.util.List;

/**
 * Client for the PromoStandards Media Content Service (1.1.0).
 *
 * <p>Mirrors the SOAP operations so {@link StubMediaClient} can be swapped for a real
 * SOAP-backed implementation via {@code promostandards.media.mode=soap}.
 */
public interface MediaClient {

	/** {@code getMediaContent} — media assets for a product, optionally filtered by type/class. */
	List<MediaContent> getMediaContent(GetMediaContentRequest request);

	/** {@code getMediaDateModified} — products whose media changed since the requested timestamp. */
	List<MediaDateModified> getMediaDateModified(GetMediaDateModifiedRequest request);
}
