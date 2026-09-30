package com.fixmycity.ai;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

import com.fixmycity.common.ApiException;
import com.fixmycity.complaint.Priority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** REST client for the FastAPI AI service. Every call carries the shared internal key. */
@Component
public class AiClient {

	private static final Logger log = LoggerFactory.getLogger(AiClient.class);

	public record CategoryIn(Long id, String name, String description, Long departmentId) {
	}

	public record DepartmentIn(Long id, String name, String description) {
	}

	public record AnalyzeRequest(String title, String description, String location, Long categoryId, String imageUrl,
			List<CategoryIn> categories, List<DepartmentIn> departments) {
	}

	public record Analysis(Long categoryId, Long departmentId, Priority priority, String summary, String imageStatus,
			String imageFindings, float[] embedding, String model) {
	}

	public record WriteRequest(String title, String description, String location) {
	}

	public record Suggestion(String title, String description, List<String> missingDetails, String model) {
	}

	public record Source(String title, String source) {
	}

	public record Answer(String answer, boolean grounded, List<Source> sources, String model) {
	}

	public record ComplaintHit(Long complaintId, double score) {
	}

	public record KnowledgeHit(String source, String title, String content, double score) {
	}

	private record SearchRequest(String query, Long citizenId, Long officerId, int limit) {
	}

	private record QuestionRequest(String question) {
	}

	private final RestClient interactive;

	private final RestClient background;

	AiClient(RestClient.Builder builder, @Value("${fmc.ai.base-url}") String baseUrl,
			@Value("${fmc.ai.api-key}") String apiKey) {
		this.interactive = client(builder, baseUrl, apiKey, Duration.ofSeconds(45));
		this.background = client(builder, baseUrl, apiKey, Duration.ofSeconds(150));
	}

	private static RestClient client(RestClient.Builder builder, String baseUrl, String apiKey, Duration readTimeout) {
		// HTTP/1.1: the AI service does not speak cleartext HTTP/2, so skip the upgrade attempt.
		var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5));
		var factory = new JdkClientHttpRequestFactory(http.build());
		factory.setReadTimeout(readTimeout);
		return builder.clone().baseUrl(baseUrl).defaultHeader("X-Internal-Key", apiKey).requestFactory(factory).build();
	}

	/** Background use: failures propagate so the caller can retry. */
	public Analysis analyze(AnalyzeRequest request) {
		return this.background.post().uri("/analyze").body(request).retrieve().body(Analysis.class);
	}

	/** Background use: re-embeds changed knowledge-base chunks. */
	public void syncKnowledge() {
		this.background.post().uri("/knowledge/sync").retrieve().toBodilessEntity();
	}

	public Suggestion improve(WriteRequest request) {
		return userFacing(() -> this.interactive.post().uri("/assist/write").body(request).retrieve().body(Suggestion.class));
	}

	public Answer ask(String question) {
		return userFacing(() -> this.interactive.post()
			.uri("/assistant/ask")
			.body(new QuestionRequest(question))
			.retrieve()
			.body(Answer.class));
	}

	public List<ComplaintHit> searchComplaints(String query, Long citizenId, Long officerId, int limit) {
		return userFacing(() -> this.interactive.post()
			.uri("/search/complaints")
			.body(new SearchRequest(query, citizenId, officerId, limit))
			.retrieve()
			.body(new ParameterizedTypeReference<List<ComplaintHit>>() {
			}));
	}

	public List<KnowledgeHit> searchKnowledge(String query, int limit) {
		return userFacing(() -> this.interactive.post()
			.uri("/search/knowledge")
			.body(new SearchRequest(query, null, null, limit))
			.retrieve()
			.body(new ParameterizedTypeReference<List<KnowledgeHit>>() {
			}));
	}

	/** A user is waiting: turn AI outages into a clear, retryable message. */
	private static <T> T userFacing(Supplier<T> call) {
		try {
			return call.get();
		}
		catch (RestClientException ex) {
			log.warn("AI service call failed: {}", ex.getMessage());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
					"The AI assistant is temporarily unavailable. Please try again.");
		}
	}

}
