package com.trophy.promostandards.media.web;

import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.model.MediaDateModified;
import com.trophy.promostandards.media.service.MediaService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * REST facade for the PromoStandards Media Content Service.
 */
@RestController
@RequestMapping("/api/media")
public class MediaController {

	private final MediaService service;

	public MediaController(MediaService service) {
		this.service = service;
	}

	/** {@code getMediaContent} — media for a product, optional {@code mediaType}/{@code classType} filters. */
	@GetMapping("/{productId}")
	public List<MediaContent> getMediaContent(@PathVariable String productId,
			@RequestParam(required = false) String mediaType,
			@RequestParam(required = false) Integer classType) {
		return service.getMediaContent(productId, mediaType, classType);
	}

	/** {@code getMediaDateModified} — products whose media changed since {@code changedSince}. */
	@GetMapping("/date-modified")
	public List<MediaDateModified> getMediaDateModified(
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant changedSince) {
		return service.getMediaDateModified(changedSince);
	}
}
