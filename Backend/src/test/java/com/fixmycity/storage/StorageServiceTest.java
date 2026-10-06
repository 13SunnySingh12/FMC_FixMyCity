package com.fixmycity.storage;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Configuration and URL signing only; nothing here contacts Backblaze B2. */
class StorageServiceTest {

	private static final Duration TEN_MINUTES = Duration.ofMinutes(10);

	@Test
	void aMalformedEndpointIsRejectedWithAClearMessage() {
		for (String endpoint : List.of("s3.us_west.backblazeb2.com", "storage.example.com", "s3..backblazeb2.com",
				"s3.us west.backblazeb2.com")) {
			assertThatThrownBy(() -> new StorageService(endpoint, "fmc-test", "key-id", "application-key", TEN_MINUTES))
				.as(endpoint)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("B2_ENDPOINT must look like s3.<region>.backblazeb2.com");
		}
	}

	@Test
	void missingSettingsAreNamed() {
		assertThatThrownBy(() -> new StorageService("s3.us-west-004.backblazeb2.com", "fmc-test", " ", "", TEN_MINUTES))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("B2_KEY_ID");
	}

	@Test
	void signedUrlsPointAtBackblazeAndExpire() {
		// The scheme and a trailing slash are tolerated, as people paste the endpoint in either form.
		StorageService storage = new StorageService("https://s3.us-west-004.backblazeb2.com/", "fmc-test", "key-id",
				"application-key", TEN_MINUTES);
		try {
			assertThat(storage.signedUrl("complaints/7/photo.jpg")).startsWith("https://")
				.contains(".backblazeb2.com/") // the AI service fetches images from this host suffix only
				.contains("complaints/7/photo.jpg")
				.contains("X-Amz-Expires=600")
				.contains("X-Amz-Signature=");
		}
		finally {
			storage.close();
		}
	}

}
