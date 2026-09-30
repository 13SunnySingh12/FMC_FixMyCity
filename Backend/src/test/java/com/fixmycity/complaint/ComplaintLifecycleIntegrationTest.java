package com.fixmycity.complaint;

import java.time.Instant;
import java.util.UUID;

import com.fixmycity.IntegrationTest;
import com.fixmycity.TestApi;
import com.fixmycity.TestApi.Session;
import com.fixmycity.common.ApiException;
import com.fixmycity.storage.ImageFile;
import com.fixmycity.storage.StorageService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class ComplaintLifecycleIntegrationTest {

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F' };

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 0x0D };

	@Autowired
	MockMvc mvc;

	@Autowired
	StorageService storage;

	TestApi api;

	Session admin;

	long roadsCategory;

	long roadsDepartment;

	@BeforeEach
	void setUp() throws Exception {
		this.api = new TestApi(this.mvc);
		this.admin = this.api.admin();
		this.roadsCategory = this.api.categoryId("Roads");
		this.roadsDepartment = this.api.departmentId("Roads & Public Works");
		given(this.storage.signedUrl(anyString())).willAnswer((call) -> "https://signed.example/" + call.getArgument(0));
	}

	@Test
	void citizenSubmitsWithImageStoredUnderFmcObjectKeyLayout() throws Exception {
		Session citizen = this.api.citizen();
		long id = TestApi.id(submit(citizen, UUID.randomUUID(), jpeg("pothole.jpg"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.departmentName").value("Roads & Public Works"))
			.andExpect(jsonPath("$.ai.status").value("PENDING"))
			.andExpect(jsonPath("$.images", hasSize(1)))
			.andExpect(jsonPath("$.images[0].name").value("pothole.jpg"))
			.andExpect(jsonPath("$.timeline[0].status").value("SUBMITTED")));

		verify(this.storage).putInTransaction(matches("complaints/" + id + "/[0-9a-f-]{36}\\.jpg"),
				argThat((ImageFile image) -> image.contentType().equals("image/jpeg")));
		this.api.get("/api/complaints/" + id, citizen)
			.andExpect(jsonPath("$.images[0].url", startsWith("https://signed.example/complaints/" + id + "/")));
	}

	@Test
	void retriedSubmissionReturnsTheSameComplaint() throws Exception {
		Session citizen = this.api.citizen();
		UUID requestId = UUID.randomUUID();
		long first = TestApi.id(submit(citizen, requestId, null).andExpect(status().isCreated()));

		submit(citizen, requestId, null).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(first));
		this.api.get("/api/complaints", citizen).andExpect(jsonPath("$.totalItems").value(1));
	}

	@Test
	void rejectsUploadsThatAreNotReallyImages() throws Exception {
		Session citizen = this.api.citizen();
		var fake = new MockMultipartFile("image", "photo.jpg", "image/jpeg", "<html>not an image</html>".getBytes());

		submit(citizen, UUID.randomUUID(), fake).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Only JPEG, PNG or WebP images are accepted."));
		verify(this.storage, never()).putInTransaction(anyString(), any());
		this.api.get("/api/complaints", citizen).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void storageOutageRollsBackTheWholeSubmission() throws Exception {
		Session citizen = this.api.citizen();
		willThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage is temporarily unavailable."))
			.given(this.storage).putInTransaction(anyString(), any());

		submit(citizen, UUID.randomUUID(), jpeg("pothole.jpg")).andExpect(status().isServiceUnavailable());
		this.api.get("/api/complaints", citizen).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void complaintsAreVisibleOnlyToOwnerAssignedOfficerAndAdmins() throws Exception {
		Session owner = this.api.citizen();
		Session officer = this.api.officer(this.roadsDepartment);
		long id = TestApi.id(submit(owner, UUID.randomUUID(), null));

		this.api.get("/api/complaints/" + id, this.api.citizen()).andExpect(status().isNotFound());
		this.api.get("/api/complaints/" + id, officer).andExpect(status().isNotFound());
		this.api.get("/api/complaints/" + id, this.admin).andExpect(status().isOk())
			.andExpect(jsonPath("$.actions", hasItem("ASSIGN")));

		assign(id, this.admin, officer.id()).andExpect(status().isOk());
		this.api.get("/api/complaints/" + id, officer).andExpect(status().isOk());
		this.api.get("/api/complaints", officer).andExpect(jsonPath("$.items[*].id", hasItem((int) id)));
		this.api.get("/api/complaints", this.api.citizen()).andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void fullLifecycleRequiresEvidenceAndSupportsReopenAndFeedback() throws Exception {
		Session citizen = this.api.citizen();
		Session officer = this.api.officer(this.roadsDepartment);
		long id = TestApi.id(submit(citizen, UUID.randomUUID(), jpeg("pothole.jpg")));
		String path = "/api/complaints/" + id;

		assign(id, this.admin, officer.id()).andExpect(jsonPath("$.status").value("ASSIGNED"))
			.andExpect(jsonPath("$.assignedOfficer.id").value(officer.id()));
		this.api.post(path + "/start", officer, "{}").andExpect(jsonPath("$.status").value("IN_PROGRESS"))
			.andExpect(jsonPath("$.actions", not(hasItem("RESOLVE"))));
		this.api.post(path + "/resolve", officer, "{}").andExpect(status().isConflict());

		this.api.post(path + "/notes", officer, "{\"body\": \"Filled the pothole with cold-mix asphalt.\"}")
			.andExpect(jsonPath("$.notes", hasSize(1)));
		uploadProof(id, officer).andExpect(status().isOk()).andExpect(jsonPath("$.proofs", hasSize(1)))
			.andExpect(jsonPath("$.actions", hasItem("RESOLVE")));
		verify(this.storage).putInTransaction(matches("resolution-proofs/" + id + "/[0-9a-f-]{36}\\.png"), any());
		this.api.post(path + "/resolve", officer, "{\"note\": \"Road surface restored\"}")
			.andExpect(jsonPath("$.status").value("RESOLVED"));

		this.api.get(path, citizen).andExpect(jsonPath("$.actions", contains("REOPEN", "FEEDBACK")));
		this.api.post(path + "/reopen", citizen, "{\"reason\": \"The pothole opened up again after rain.\"}")
			.andExpect(jsonPath("$.status").value("ASSIGNED"))
			.andExpect(jsonPath("$.assignedOfficer.id").value(officer.id()));

		this.api.post(path + "/start", officer, "{}").andExpect(status().isOk());
		this.api.post(path + "/resolve", officer, "{}").andExpect(status().isConflict()); // old evidence does not count
		this.api.post(path + "/notes", officer, "{\"body\": \"Relaid the patch with hot-mix.\"}");
		uploadProof(id, officer);
		this.api.post(path + "/resolve", officer, "{}").andExpect(jsonPath("$.status").value("RESOLVED"));

		this.api.post(path + "/feedback", citizen, "{\"rating\": 4, \"comment\": \"Fixed properly now.\"}")
			.andExpect(jsonPath("$.status").value("CLOSED"))
			.andExpect(jsonPath("$.feedback.rating").value(4))
			.andExpect(jsonPath("$.timeline[*].status", contains("SUBMITTED", "ASSIGNED", "IN_PROGRESS", "RESOLVED",
					"ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED")));
		this.api.post(path + "/feedback", citizen, "{\"rating\": 5}").andExpect(status().isConflict());
	}

	@Test
	void newEvidenceCountsAsAnUpdateForTheCitizen() throws Exception {
		Session citizen = this.api.citizen();
		Session officer = this.api.officer(this.roadsDepartment);
		long id = TestApi.id(submit(citizen, UUID.randomUUID(), null));
		String path = "/api/complaints/" + id;
		assign(id, this.admin, officer.id());

		Instant started = updatedAt(this.api.post(path + "/start", officer, "{}"));
		Instant noted = updatedAt(this.api.post(path + "/notes", officer, "{\"body\": \"Inspected the site.\"}"));
		assertThat(noted).isAfter(started);
		assertThat(updatedAt(uploadProof(id, officer))).isAfter(noted);
	}

	@Test
	void malformedIdsGetAPlainAnswer() throws Exception {
		this.api.get("/api/complaints/not-a-number", this.api.citizen())
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("Some of the information sent is not valid. Check it and try again."));
	}

	private static Instant updatedAt(ResultActions result) throws Exception {
		return Instant.parse(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.updatedAt"));
	}

	@Test
	void officerReassignsToAColleagueOrBackToADepartment() throws Exception {
		Session citizen = this.api.citizen();
		Session first = this.api.officer(this.roadsDepartment);
		Session second = this.api.officer(this.api.departmentId("Drainage & Sewerage"));
		long id = TestApi.id(submit(citizen, UUID.randomUUID(), null));
		String path = "/api/complaints/" + id;
		assign(id, this.admin, first.id());

		this.api.post(path + "/assignment", first,
				"{\"officerId\": %d, \"note\": \"Drain collapse, not a road issue\"}".formatted(second.id()))
			.andExpect(jsonPath("$.status").value("ASSIGNED"))
			.andExpect(jsonPath("$.departmentName").value("Drainage & Sewerage"))
			.andExpect(jsonPath("$.timeline[-1].note", containsString("Reassigned from")));
		this.api.get(path, first).andExpect(status().isNotFound());

		this.api.post(path + "/assignment", second, "{\"departmentId\": %d}".formatted(this.roadsDepartment))
			.andExpect(jsonPath("$.status").value("SUBMITTED"))
			.andExpect(jsonPath("$.assignedOfficer").doesNotExist());
		this.api.post(path + "/assignment", this.admin, "{\"officerId\": 1, \"departmentId\": 1}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void illegalTransitionsAndRolesAreRejected() throws Exception {
		Session citizen = this.api.citizen();
		Session officer = this.api.officer(this.roadsDepartment);
		long id = TestApi.id(submit(citizen, UUID.randomUUID(), null));
		String path = "/api/complaints/" + id;
		assign(id, this.admin, officer.id());

		this.api.post(path + "/start", citizen, "{}").andExpect(status().isForbidden());
		this.api.post(path + "/reopen", citizen, "{\"reason\": \"Still broken, please check.\"}")
			.andExpect(status().isConflict());
		this.api.post(path + "/feedback", citizen, "{\"rating\": 3}").andExpect(status().isConflict());
		this.api.post(path + "/reopen", officer, "{\"reason\": \"Officers cannot reopen.\"}")
			.andExpect(status().isForbidden());
		this.api.post(path + "/feedback", citizen, "{\"rating\": 9}").andExpect(status().isBadRequest());
	}

	@Test
	void adminManagesComplaintsAndOfficersWithOpenWorkAreProtected() throws Exception {
		Session citizen = this.api.citizen();
		Session officer = this.api.officer(this.roadsDepartment);
		long id = TestApi.id(submit(citizen, UUID.randomUUID(), null));
		assign(id, this.admin, officer.id());

		this.api.patch("/api/complaints/" + id, this.admin, "{\"priority\": \"HIGH\"}")
			.andExpect(jsonPath("$.priority").value("HIGH"));
		this.api.patch("/api/admin/users/" + officer.id(), this.admin, "{\"active\": false}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("Reassign this officer's open complaints first."));
		this.api.get("/api/complaints?status=ASSIGNED&priority=HIGH", this.admin)
			.andExpect(jsonPath("$.items[*].id", hasItem((int) id)));
		this.api.post("/api/complaints/" + id + "/close", this.admin, "{}").andExpect(status().isConflict());
	}

	private ResultActions submit(Session citizen, UUID requestId, MockMultipartFile image) throws Exception {
		MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart("/api/complaints");
		if (image != null) {
			request.file(image);
		}
		request.param("title", "Deep pothole on MG Road")
			.param("description", "A deep pothole near the bus stop is damaging vehicles and is dangerous at night.")
			.param("location", "MG Road, near the central bus stop")
			.param("categoryId", String.valueOf(this.roadsCategory))
			.param("requestId", requestId.toString());
		return this.api.perform(request, citizen);
	}

	private ResultActions assign(long id, Session actor, long officerId) throws Exception {
		return this.api.post("/api/complaints/" + id + "/assignment", actor, "{\"officerId\": %d}".formatted(officerId));
	}

	private ResultActions uploadProof(long id, Session officer) throws Exception {
		return this.api.perform(MockMvcRequestBuilders.multipart("/api/complaints/" + id + "/proofs")
			.file(new MockMultipartFile("images", "after.png", "image/png", PNG)), officer);
	}

	private static MockMultipartFile jpeg(String name) {
		return new MockMultipartFile("image", name, "image/jpeg", JPEG);
	}

}
