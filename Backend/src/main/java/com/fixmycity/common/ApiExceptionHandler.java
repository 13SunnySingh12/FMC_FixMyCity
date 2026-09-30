package com.fixmycity.common;

import java.util.LinkedHashMap;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	static final String UNAVAILABLE = "FixMyCity is temporarily unavailable. Please try again in a moment.";

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ProblemDetail handleApiException(ApiException ex) {
		return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
		// Log the constraint name only; the driver message can contain user data.
		String constraint = ex.getCause() instanceof ConstraintViolationException cve ? cve.getConstraintName() : "unknown";
		log.warn("Rejected write violating constraint {}", constraint);
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "The request conflicts with existing data.");
	}

	@ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class })
	ProblemDetail handleDatabaseUnavailable(RuntimeException ex) {
		log.error("Database unavailable: {}", ex.getMessage());
		return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, UNAVAILABLE);
	}

	@ExceptionHandler(OptimisticLockingFailureException.class)
	ProblemDetail handleStaleUpdate(OptimisticLockingFailureException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
				"This item was changed by someone else. Reload and try again.");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult().getFieldErrors().forEach((e) -> errors.putIfAbsent(e.getField(), e.getDefaultMessage()));
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Some fields are invalid.");
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}

	/** Framework messages are technical: users get a plain one, the log keeps the original. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		log.info("Request rejected with {}: {}", status.value(), ex.getMessage());
		return super.handleExceptionInternal(ex, ProblemDetail.forStatusAndDetail(status, plainMessage(status)),
				headers, status, request);
	}

	private static String plainMessage(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "Some of the information sent is not valid. Check it and try again.";
			case 404 -> "Not found.";
			case 405, 406, 415 -> "This request is not supported.";
			case 413 -> "The file is too large. Each photo must be 5 MB or smaller.";
			case 503 -> UNAVAILABLE;
			default -> "Something went wrong. Please try again.";
		};
	}

}
