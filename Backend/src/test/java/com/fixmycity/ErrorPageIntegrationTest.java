package com.fixmycity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.fixmycity.ai.AiClient;
import com.fixmycity.storage.StorageService;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the real embedded server: a request refused before it reaches a controller is answered through the
 * container's error page, which MockMvc does not exercise.
 * MOCK/TEST INTEGRATION: Backblaze B2 and the FastAPI AI service are Mockito mocks here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
@MockitoBean(types = { StorageService.class, AiClient.class })
class ErrorPageIntegrationTest {

	@Value("${local.server.port}")
	int port;

	@Test
	void aMalformedAddressIsABadRequestNotAnEndedSession() throws Exception {
		// The browser signs the user out on 401, so these must not be answered as "not signed in".
		assertThat(status("/api/complaints/1;x")).isEqualTo(400);
		assertThat(status("/api//complaints")).isEqualTo(400);
	}

	@Test
	void protectedPathsStillRequireSigningIn() throws Exception {
		assertThat(status("/api/complaints/1")).isEqualTo(401);
		assertThat(status("/error")).isEqualTo(401);
	}

	private int status(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + this.port + path)).build();
		try (HttpClient client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
		}
	}

}
