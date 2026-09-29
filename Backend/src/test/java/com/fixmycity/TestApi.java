package com.fixmycity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Drives the real API as each role, so tests exercise the same security rules as production. */
public class TestApi {

	public static final String PASSWORD = "correct horse battery";

	public record Session(long id, String token) {

		Cookie cookie() {
			return new Cookie("fmc_token", this.token);
		}

	}

	private final MockMvc mvc;

	public TestApi(MockMvc mvc) {
		this.mvc = mvc;
	}

	public Session admin() throws Exception {
		return login("admin@test.local", "test-only-admin-password");
	}

	public Session citizen() throws Exception {
		return session(post("/api/auth/register", null,
				"{\"name\": \"Test Citizen\", \"email\": \"%s\", \"password\": \"%s\"}".formatted(uniqueEmail(), PASSWORD)));
	}

	public Session officer(long departmentId) throws Exception {
		String email = uniqueEmail();
		post("/api/admin/officers", admin(), """
				{"name": "Test Officer", "email": "%s", "password": "%s", "departmentId": %d}
				""".formatted(email, PASSWORD, departmentId));
		return login(email, PASSWORD);
	}

	public Session login(String email, String password) throws Exception {
		return session(post("/api/auth/login", null,
				"{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password)));
	}

	public long departmentId(String name) throws Exception {
		String body = get("/api/departments", admin()).andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> matches = JsonPath.read(body, "$[?(@.name == '" + name + "')]");
		return ((Number) matches.getFirst().get("id")).longValue();
	}

	public long categoryId(String name) throws Exception {
		String body = get("/api/categories", admin()).andReturn().getResponse().getContentAsString();
		List<Map<String, Object>> matches = JsonPath.read(body, "$[?(@.name == '" + name + "')]");
		return ((Number) matches.getFirst().get("id")).longValue();
	}

	public ResultActions get(String path, Session session) throws Exception {
		return perform(MockMvcRequestBuilders.get(path), session);
	}

	public ResultActions post(String path, Session session, String json) throws Exception {
		return perform(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json), session);
	}

	public ResultActions put(String path, Session session, String json) throws Exception {
		return perform(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json), session);
	}

	public ResultActions patch(String path, Session session, String json) throws Exception {
		return perform(MockMvcRequestBuilders.patch(path).contentType(MediaType.APPLICATION_JSON).content(json), session);
	}

	public ResultActions delete(String path, Session session) throws Exception {
		return perform(MockMvcRequestBuilders.delete(path), session);
	}

	public ResultActions perform(MockHttpServletRequestBuilder request, Session session) throws Exception {
		return this.mvc.perform((session == null) ? request : request.cookie(session.cookie()));
	}

	public static long id(ResultActions result) throws Exception {
		return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
	}

	public static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	public static String unique(String prefix) {
		return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
	}

	private static Session session(ResultActions result) throws Exception {
		var response = result.andReturn().getResponse();
		if (response.getStatus() >= 300) {
			throw new IllegalStateException("Authentication failed with HTTP " + response.getStatus());
		}
		return new Session(id(result), response.getCookie("fmc_token").getValue());
	}

}
