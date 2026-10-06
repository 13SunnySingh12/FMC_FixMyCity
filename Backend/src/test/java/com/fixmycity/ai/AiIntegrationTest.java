package com.fixmycity.ai;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.fixmycity.IntegrationTest;
import com.fixmycity.TestApi;
import com.fixmycity.TestApi.Session;
import com.fixmycity.ai.AiClient.Analysis;
import com.fixmycity.ai.AiClient.Answer;
import com.fixmycity.ai.AiClient.ComplaintHit;
import com.fixmycity.ai.AiClient.Suggestion;
import com.fixmycity.common.ApiException;
import com.fixmycity.complaint.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AiIntegrationTest {

	private static final Duration WAIT = Duration.ofSeconds(15);

	@Autowired
	MockMvc mvc;

	@Autowired
	AiClient ai;

	@Autowired
	AiJobs jobs;

	@Autowired
	JdbcTemplate jdbc;

	TestApi api;

	Session admin;

	long roads;

	long streetlights;

	long lightingDepartment;

	@BeforeEach
	void setUp() throws Exception {
		this.api = new TestApi(this.mvc);
		this.admin = this.api.admin();
		this.roads = this.api.categoryId("Roads");
		this.streetlights = this.api.categoryId("Streetlights");
		this.lightingDepartment = this.api.departmentId("Street Lighting & Electrical");
	}

	@Test
	void analysisRunsInTheBackgroundAndStoresValidatedSuggestions() throws Exception {
		given(this.ai.analyze(any())).willReturn(analysis(this.streetlights, Priority.HIGH));
		Session citizen = this.api.citizen();
		long id = submit(citizen);

		awaitAiStatus(id, citizen, "COMPLETED");
		this.api.get("/api/complaints/" + id, citizen)
			.andExpect(jsonPath("$.ai.categoryName").value("Streetlights"))
			.andExpect(jsonPath("$.ai.departmentName").value("Street Lighting & Electrical"))
			.andExpect(jsonPath("$.ai.summary").value("Streetlight out near the school."))
			.andExpect(jsonPath("$.priority").value("HIGH"))
			.andExpect(jsonPath("$.categoryName").value("Roads"));
		assertThat(this.jdbc.queryForObject("SELECT vector_dims(embedding) FROM complaints WHERE id = ?", Integer.class, id))
			.isEqualTo(768);
	}

	@Test
	void failedAnalysisIsRetriedAutomatically() throws Exception {
		given(this.ai.analyze(any())).willThrow(new ResourceAccessException("timeout"))
			.willReturn(analysis(this.streetlights, Priority.MEDIUM));
		Session citizen = this.api.citizen();
		long id = submit(citizen);

		awaitAiStatus(id, citizen, "COMPLETED");
		assertThat(this.jdbc.queryForObject("SELECT ai_attempts FROM complaints WHERE id = ?", Integer.class, id))
			.isEqualTo(2);
	}

	@Test
	void analysisGivesUpAfterMaxAttemptsAndAdminsCanRetry() throws Exception {
		given(this.ai.analyze(any())).willThrow(new ResourceAccessException("down"));
		Session citizen = this.api.citizen();
		long id = submit(citizen);

		awaitAiStatus(id, citizen, "FAILED");
		this.api.get("/api/complaints/" + id, this.admin)
			.andExpect(jsonPath("$.actions", hasItem("RETRY_AI")))
			.andExpect(jsonPath("$.ai.error").value("The AI service could not be reached."));
		this.api.get("/api/complaints/" + id, citizen).andExpect(jsonPath("$.ai.error").doesNotExist());
		this.api.post("/api/complaints/" + id + "/analysis/retry", citizen, "{}").andExpect(status().isForbidden());

		// willReturn(..).given(..) re-stubs without invoking the currently throwing stub.
		willReturn(analysis(this.streetlights, Priority.LOW)).given(this.ai).analyze(any());
		this.api.post("/api/complaints/" + id + "/analysis/retry", this.admin, "{}").andExpect(status().isAccepted());
		awaitAiStatus(id, citizen, "COMPLETED");
		this.api.post("/api/complaints/" + id + "/analysis/retry", this.admin, "{}").andExpect(status().isConflict());
		this.api.post("/api/complaints/999999/analysis/retry", this.admin, "{}").andExpect(status().isNotFound());
	}

	@Test
	void modelOutputOutsideTheOfferedIdsIsNeverStored() throws Exception {
		given(this.ai.analyze(any())).willReturn(analysis(999_999L, Priority.HIGH));
		Session citizen = this.api.citizen();
		long id = submit(citizen);

		awaitAiStatus(id, citizen, "FAILED");
		assertThat(this.jdbc.queryForObject("SELECT ai_category_id FROM complaints WHERE id = ?", Long.class, id)).isNull();
		assertThat(this.jdbc.queryForObject("SELECT ai_error FROM complaints WHERE id = ?", String.class, id))
			.isEqualTo("The AI answer did not pass validation.");
	}

	@Test
	void aiNeverOverwritesAPriorityAPersonSetDuringAnalysis() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		given(this.ai.analyze(any())).willAnswer((call) -> {
			release.await(10, TimeUnit.SECONDS);
			return analysis(this.streetlights, Priority.HIGH);
		});
		Session citizen = this.api.citizen();
		long id = submit(citizen);
		awaitAiStatus(id, citizen, "PROCESSING");

		this.api.patch("/api/complaints/" + id, this.admin, "{\"priority\": \"LOW\"}").andExpect(status().isOk());
		release.countDown();

		awaitAiStatus(id, citizen, "COMPLETED");
		this.api.get("/api/complaints/" + id, citizen)
			.andExpect(jsonPath("$.priority").value("LOW"))
			.andExpect(jsonPath("$.ai.priority").value("HIGH"));
	}

	@Test
	void writingAssistantAndCivicAssistantAreProxied() throws Exception {
		given(this.ai.improve(any())).willReturn(new Suggestion("Streetlight out on School Lane",
				"The streetlight outside the school has been off for a week.", List.of("Pole number?"), "fake:model"));
		given(this.ai.ask("Which department handles streetlights?")).willReturn(new Answer(
				"Street Lighting & Electrical.", true, List.of(new AiClient.Source("Streetlights", "directory")),
				"fake:model"));
		Session citizen = this.api.citizen();

		this.api.post("/api/ai/write", citizen, "{\"description\": \"light near school not working since a week\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("Streetlight out on School Lane"))
			.andExpect(jsonPath("$.missingDetails[0]").value("Pole number?"));
		this.api.post("/api/ai/write", this.admin, "{\"description\": \"admins do not write complaints\"}")
			.andExpect(status().isForbidden());
		this.api.post("/api/assistant/ask", citizen, "{\"question\": \"Which department handles streetlights?\"}")
			.andExpect(jsonPath("$.grounded").value(true))
			.andExpect(jsonPath("$.sources", hasSize(1)));
	}

	@Test
	void semanticSearchUsesTheCallersScopeAndRechecksVisibility() throws Exception {
		given(this.ai.analyze(any())).willReturn(analysis(this.streetlights, Priority.LOW));
		Session citizen = this.api.citizen();
		long own = submit(citizen);
		long someoneElses = submit(this.api.citizen());
		given(this.ai.searchComplaints(anyString(), any(), any(), eq(20)))
			.willReturn(List.of(new ComplaintHit(someoneElses, 0.9), new ComplaintHit(own, 0.8)));

		this.api.get("/api/search/complaints?q=  dark street ", citizen)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$", hasSize(1)))
			.andExpect(jsonPath("$[0].complaint.id").value(own))
			.andExpect(jsonPath("$[*].complaint.id", not(hasItem((int) someoneElses))));
		verify(this.ai).searchComplaints(eq("dark street"), eq(citizen.id()), isNull(), eq(20));
	}

	@Test
	void textIsTrimmedBeforeItsLengthIsCheckedSoTheAiServiceNeverRejectsIt() throws Exception {
		Session citizen = this.api.citizen();

		this.api.post("/api/assistant/ask", citizen, "{\"question\": \"a         \"}").andExpect(status().isBadRequest());
		this.api.post("/api/ai/write", citizen, "{\"description\": \"pothole              \"}")
			.andExpect(status().isBadRequest());
		this.api.get("/api/search/knowledge?q=a   ", citizen).andExpect(status().isBadRequest());
		this.api.get("/api/search/complaints?q=a   ", citizen).andExpect(status().isBadRequest());

		verify(this.ai, never()).ask(anyString());
		verify(this.ai, never()).improve(any());
		verify(this.ai, never()).searchKnowledge(anyString(), anyInt());
		verify(this.ai, never()).searchComplaints(anyString(), any(), any(), anyInt());
	}

	@Test
	void changingDepartmentsRefreshesTheKnowledgeBase() throws Exception {
		this.api.post("/api/admin/departments", this.admin, "{\"name\": \"%s\"}".formatted(TestApi.unique("Parks")))
			.andExpect(status().isCreated());
		verify(this.ai, timeout(WAIT.toMillis())).syncKnowledge();
	}

	@Test
	void startupLoadsTheKnowledgeBaseOnceTheTablesExist() {
		// On a new database the AI service starts before the migrations run, so its own first sync finds no tables.
		this.jobs.resumeUnfinished();

		verify(this.ai, timeout(WAIT.toMillis()).atLeastOnce()).syncKnowledge();
	}

	@Test
	void aiOutagesAndOveruseGetClearResponses() throws Exception {
		Session citizen = this.api.citizen();
		given(this.ai.ask(anyString())).willThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
				"The AI assistant is temporarily unavailable. Please try again."));
		this.api.post("/api/assistant/ask", citizen, "{\"question\": \"How do I report a pothole?\"}")
			.andExpect(status().isServiceUnavailable());

		willReturn(new Answer("ok", true, List.of(), "fake:model")).given(this.ai).ask(anyString());
		for (int i = 0; i < 19; i++) {
			this.api.post("/api/assistant/ask", citizen, "{\"question\": \"Question %d?\"}".formatted(i))
				.andExpect(status().isOk());
		}
		this.api.post("/api/assistant/ask", citizen, "{\"question\": \"One too many?\"}")
			.andExpect(status().isTooManyRequests());
	}

	private long submit(Session citizen) throws Exception {
		return TestApi.id(this.api.perform(MockMvcRequestBuilders.multipart("/api/complaints")
			.param("title", "Streetlight not working")
			.param("description", "The streetlight outside the school has been off for a week; the lane is dark.")
			.param("location", "School Lane")
			.param("categoryId", String.valueOf(this.roads))
			.param("requestId", UUID.randomUUID().toString()), citizen).andExpect(status().isCreated()));
	}

	private void awaitAiStatus(long id, Session viewer, String expected) {
		await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> this.api
			.get("/api/complaints/" + id, viewer)
			.andExpect(jsonPath("$.ai.status").value(expected)));
	}

	private Analysis analysis(long categoryId, Priority priority) {
		float[] embedding = new float[768];
		Arrays.fill(embedding, 0.036f);
		return new Analysis(categoryId, this.lightingDepartment, priority, "Streetlight out near the school.", "NONE",
				null, embedding, "fake:model");
	}

}
