package com.fixmycity.admin;

import java.util.UUID;

import com.fixmycity.IntegrationTest;
import com.fixmycity.TestApi;
import com.fixmycity.TestApi.Session;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AnalyticsIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Test
	void analyticsCountComplaintsByStatusCategoryAndPriority() throws Exception {
		TestApi api = new TestApi(this.mvc);
		Session admin = api.admin();
		String before = snapshot(api, admin);
		Session citizen = api.citizen();
		long id = TestApi.id(api.perform(MockMvcRequestBuilders.multipart("/api/complaints")
			.param("title", "Garbage not collected")
			.param("description", "Garbage has not been collected on our street for over a week.")
			.param("location", "Lane 4, Sector 9")
			.param("categoryId", String.valueOf(api.categoryId("Garbage")))
			.param("requestId", UUID.randomUUID().toString()), citizen));
		api.patch("/api/complaints/" + id, admin, "{\"priority\": \"HIGH\"}").andExpect(status().isOk());
		String after = snapshot(api, admin);

		assertThat(delta(before, after, "$.total")).isEqualTo(1);
		assertThat(delta(before, after, "$.pending")).isEqualTo(1);
		assertThat(delta(before, after, "$.resolved")).isZero();
		assertThat(delta(before, after, "$.byStatus.SUBMITTED")).isEqualTo(1);
		assertThat(delta(before, after, "$.byPriority.HIGH")).isEqualTo(1);
		assertThat(delta(before, after, "$.byCategory[?(@.name == 'Garbage')].count")).isEqualTo(1);
		api.get("/api/admin/analytics", citizen).andExpect(status().isForbidden());
	}

	private static String snapshot(TestApi api, Session admin) throws Exception {
		return api.get("/api/admin/analytics", admin)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.byStatus.CLOSED").exists())
			.andExpect(jsonPath("$.byPriority.UNSET").exists())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static long delta(String before, String after, String path) {
		return value(after, path) - value(before, path);
	}

	private static long value(String json, String path) {
		Object value = JsonPath.read(json, path);
		return (value instanceof java.util.List<?> list) ? ((Number) list.getFirst()).longValue() : ((Number) value).longValue();
	}

}
