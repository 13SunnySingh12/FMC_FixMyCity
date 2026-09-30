package com.fixmycity.common;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The sign-in check reads the database before any controller runs, so an outage there never reaches
 * {@link ApiExceptionHandler}. This answers it with the same 503 instead of a bare 500.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class DatabaseUnavailableFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(DatabaseUnavailableFilter.class);

	static final String BODY = "{\"title\":\"Service Unavailable\",\"status\":503,\"detail\":\""
			+ ApiExceptionHandler.UNAVAILABLE + "\"}";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		try {
			chain.doFilter(request, response);
		}
		catch (DataAccessResourceFailureException | CannotCreateTransactionException ex) {
			if (response.isCommitted()) {
				throw ex;
			}
			log.error("Database unavailable: {}", ex.getMessage());
			response.resetBuffer();
			response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
			response.setContentType("application/problem+json");
			response.getWriter().write(BODY);
		}
	}

}
