package com.fixmycity.common;

import org.junit.jupiter.api.Test;

import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class DatabaseUnavailableFilterTest {

	@Test
	void databaseOutageDuringSignInCheckBecomesAPlain503() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new DatabaseUnavailableFilter().doFilter(new MockHttpServletRequest("GET", "/api/complaints"), response,
				(request, ignored) -> {
					throw new CannotGetJdbcConnectionException("Connection refused");
				});

		assertThat(response.getStatus()).isEqualTo(503);
		assertThat(response.getContentType()).startsWith("application/problem+json");
		assertThat(response.getContentAsString()).contains(ApiExceptionHandler.UNAVAILABLE);
	}

}
