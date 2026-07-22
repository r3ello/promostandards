package com.trophy.promostandards.media.client;

import com.trophy.promostandards.common.PromoStandardsClientException;
import com.trophy.promostandards.media.model.MediaContent;
import com.trophy.promostandards.media.model.MediaRequests;
import com.trophy.promostandards.media.soap.ClassType;
import com.trophy.promostandards.media.soap.GetMediaContentResponse;
import com.trophy.promostandards.media.soap.MediaContentService;
import com.trophy.promostandards.media.soap.shared.ErrorMessage;
import com.trophy.promostandards.media.soap.shared.MediaTypeType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SOAP→model mapping in {@link SoapMediaClient}. The generated JAX-WS port is
 * mocked, so no network/endpoint is involved.
 */
class SoapMediaClientTest {

	private final MediaContentService port = mock(MediaContentService.class);
	private final SoapMediaClient client = new SoapMediaClient(port);

	private static final MediaRequests.GetMediaContentRequest REQUEST =
			new MediaRequests.GetMediaContentRequest("1.1.0", "id", "pw", "ABC", "Image", null);

	@Test
	void mapsMediaContent() {
		when(port.getMediaContent(any())).thenReturn(sampleResponse());

		List<MediaContent> media = client.getMediaContent(REQUEST);

		assertThat(media).singleElement().satisfies(m -> {
			assertThat(m.productId()).isEqualTo("ABC");
			assertThat(m.mediaType()).isEqualTo("Image");
			assertThat(m.url()).isEqualTo("https://cdn.example.com/ABC/front.jpg");
			assertThat(m.fileName()).isEqualTo("front.jpg");
			assertThat(m.classTypeId()).isEqualTo(1);
			assertThat(m.classTypeName()).isEqualTo("Front");
			assertThat(m.width()).isEqualTo(2000);
			assertThat(m.height()).isEqualTo(2000);
		});
	}

	@Test
	void errorMessageBecomesException() {
		GetMediaContentResponse response = new GetMediaContentResponse();
		ErrorMessage error = new ErrorMessage();
		error.setCode(150);
		error.setDescription("Authentication failed");
		response.setErrorMessage(error);
		when(port.getMediaContent(any())).thenReturn(response);

		assertThatThrownBy(() -> client.getMediaContent(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("Authentication failed")
				.satisfies(ex -> assertThat(((PromoStandardsClientException) ex).getServiceMessages())
						.singleElement()
						.satisfies(m -> assertThat(m.code()).isEqualTo(150)));
	}

	@Test
	void transportFailureBecomesException() {
		when(port.getMediaContent(any())).thenThrow(new RuntimeException("connection refused"));

		assertThatThrownBy(() -> client.getMediaContent(REQUEST))
				.isInstanceOf(PromoStandardsClientException.class)
				.hasMessageContaining("getMediaContent call failed");
	}

	private static GetMediaContentResponse sampleResponse() {
		ClassType classType = new ClassType();
		classType.setClassTypeId(1);
		classType.setClassTypeName("Front");

		com.trophy.promostandards.media.soap.MediaContent item = new com.trophy.promostandards.media.soap.MediaContent();
		item.setProductId("ABC");
		item.setUrl("https://cdn.example.com/ABC/front.jpg");
		item.setMediaType(MediaTypeType.IMAGE);
		item.setWidth(new BigDecimal("2000"));
		item.setHeight(new BigDecimal("2000"));
		com.trophy.promostandards.media.soap.MediaContent.ClassTypeArray classTypeArray =
				new com.trophy.promostandards.media.soap.MediaContent.ClassTypeArray();
		classTypeArray.getClassType().add(classType);
		item.setClassTypeArray(classTypeArray);

		GetMediaContentResponse.MediaContentArray array = new GetMediaContentResponse.MediaContentArray();
		array.getMediaContent().add(item);

		GetMediaContentResponse response = new GetMediaContentResponse();
		response.setMediaContentArray(array);
		return response;
	}
}
