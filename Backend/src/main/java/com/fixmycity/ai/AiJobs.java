package com.fixmycity.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.fixmycity.ai.AiClient.Analysis;
import com.fixmycity.ai.AiClient.AnalyzeRequest;
import com.fixmycity.ai.AiClient.CategoryIn;
import com.fixmycity.ai.AiClient.DepartmentIn;
import com.fixmycity.common.ApiException;
import com.fixmycity.complaint.ComplaintSubmitted;
import com.fixmycity.department.DepartmentService;
import com.fixmycity.department.ReferenceDataChanged;
import com.fixmycity.storage.StorageService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Background AI work, so nothing depends on a browser staying open. Complaint analysis state lives in the complaint
 * row (ai_status, ai_attempts): it survives restarts, and pending or interrupted analyses resume on startup.
 */
@Component
public class AiJobs {

	static final int MAX_ATTEMPTS = 4;

	private static final int EMBEDDING_DIMENSIONS = 768;

	private static final Logger log = LoggerFactory.getLogger(AiJobs.class);

	private final JdbcTemplate jdbc;

	private final AiClient ai;

	private final StorageService storage;

	private final DepartmentService departments;

	private final Duration retryDelay;

	// ponytail: in-process scheduler for a single backend instance; add a lease column or job table to scale out.
	private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

	AiJobs(JdbcTemplate jdbc, AiClient ai, StorageService storage, DepartmentService departments,
			@Value("${fmc.ai.retry-delay}") Duration retryDelay) {
		this.jdbc = jdbc;
		this.ai = ai;
		this.storage = storage;
		this.departments = departments;
		this.retryDelay = retryDelay;
	}

	@TransactionalEventListener
	void onComplaintSubmitted(ComplaintSubmitted event) {
		schedule(event.complaintId(), Duration.ZERO);
	}

	@TransactionalEventListener
	void onReferenceDataChanged(ReferenceDataChanged event) {
		this.executor.execute(() -> {
			try {
				this.ai.syncKnowledge();
			}
			catch (RuntimeException ex) {
				log.warn("Knowledge base refresh failed: {}", describe(ex));
			}
		});
	}

	@EventListener(ApplicationReadyEvent.class)
	void resumeUnfinished() {
		this.jdbc.update("UPDATE complaints SET ai_status = 'PENDING' WHERE ai_status = 'PROCESSING'");
		List<Long> pending = this.jdbc.queryForList("SELECT id FROM complaints WHERE ai_status = 'PENDING' ORDER BY id",
				Long.class);
		pending.forEach((id) -> schedule(id, Duration.ZERO));
		if (!pending.isEmpty()) {
			log.info("Resuming AI analysis for {} complaint(s)", pending.size());
		}
	}

	/** Admins can re-run an analysis that exhausted its retries. */
	public void retry(long complaintId) {
		int reset = this.jdbc.update("""
				UPDATE complaints SET ai_status = 'PENDING', ai_attempts = 0, ai_error = NULL
				WHERE id = ? AND ai_status = 'FAILED'""", complaintId);
		if (reset == 0) {
			throw ApiException.conflict("Only failed analyses can be retried.");
		}
		schedule(complaintId, Duration.ZERO);
	}

	private void schedule(long complaintId, Duration delay) {
		this.executor.schedule(() -> {
			try {
				process(complaintId);
			}
			catch (RuntimeException ex) {
				// Recording the failure itself failed (e.g. database unreachable); startup recovery resumes it.
				log.error("AI analysis bookkeeping failed for complaint {}: {}", complaintId, describe(ex));
			}
		}, delay.toMillis(), TimeUnit.MILLISECONDS);
	}

	void process(long complaintId) {
		// The atomic claim guarantees a complaint is never analysed twice at the same time.
		int claimed = this.jdbc.update("""
				UPDATE complaints SET ai_status = 'PROCESSING', ai_attempts = ai_attempts + 1, ai_updated_at = now()
				WHERE id = ? AND ai_status = 'PENDING'""", complaintId);
		if (claimed == 0) {
			return;
		}
		try {
			AnalyzeRequest request = request(complaintId);
			save(complaintId, request, this.ai.analyze(request));
		}
		catch (RuntimeException ex) {
			fail(complaintId, ex);
		}
	}

	private AnalyzeRequest request(long complaintId) {
		Map<String, Object> complaint = this.jdbc
			.queryForMap("SELECT title, description, location, category_id FROM complaints WHERE id = ?", complaintId);
		String imageKey = this.jdbc.query("""
				SELECT object_key FROM complaint_attachments
				WHERE complaint_id = ? AND kind = 'COMPLAINT_IMAGE' ORDER BY id LIMIT 1""",
				(rs) -> rs.next() ? rs.getString(1) : null, complaintId);
		return new AnalyzeRequest((String) complaint.get("title"), (String) complaint.get("description"),
				(String) complaint.get("location"), ((Number) complaint.get("category_id")).longValue(),
				(imageKey == null) ? null : this.storage.signedUrl(imageKey),
				this.departments.categories()
					.stream()
					.map((c) -> new CategoryIn(c.id(), c.name(), c.description(), c.departmentId()))
					.toList(),
				this.departments.departments()
					.stream()
					.map((d) -> new DepartmentIn(d.id(), d.name(), d.description()))
					.toList());
	}

	private void save(long complaintId, AnalyzeRequest request, Analysis analysis) {
		// Model output is never trusted blindly: ids must be ones we offered and the vector must fit the column.
		Set<Long> categoryIds = request.categories().stream().map(CategoryIn::id).collect(Collectors.toSet());
		Set<Long> departmentIds = request.departments().stream().map(DepartmentIn::id).collect(Collectors.toSet());
		if (analysis == null || !categoryIds.contains(analysis.categoryId())
				|| !departmentIds.contains(analysis.departmentId()) || analysis.priority() == null
				|| analysis.summary() == null || analysis.summary().isBlank() || analysis.embedding() == null
				|| analysis.embedding().length != EMBEDDING_DIMENSIONS) {
			throw new UnusableAnalysis();
		}
		String findings = "FAILED".equals(analysis.imageStatus()) ? "The photo could not be analysed."
				: analysis.imageFindings();
		this.jdbc.update("""
				UPDATE complaints SET ai_status = 'COMPLETED', ai_error = NULL, ai_category_id = ?, ai_department_id = ?,
				       ai_priority = ?, ai_summary = ?, ai_image_findings = ?, ai_model = ?, ai_updated_at = now(),
				       embedding = CAST(? AS vector), priority = COALESCE(priority, ?)
				WHERE id = ? AND ai_status = 'PROCESSING'""", analysis.categoryId(), analysis.departmentId(),
				analysis.priority().name(), truncate(analysis.summary().strip(), 1000), truncate(findings, 2000),
				truncate(analysis.model(), 120), vector(analysis.embedding()), analysis.priority().name(), complaintId);
		log.info("AI analysis completed for complaint {}", complaintId);
	}

	private void fail(long complaintId, RuntimeException ex) {
		int attempts = this.jdbc.queryForObject("SELECT ai_attempts FROM complaints WHERE id = ?", Integer.class,
				complaintId);
		boolean giveUp = attempts >= MAX_ATTEMPTS;
		this.jdbc.update("UPDATE complaints SET ai_status = ?, ai_error = ?, ai_updated_at = now() WHERE id = ?",
				giveUp ? "FAILED" : "PENDING", reason(ex), complaintId);
		if (giveUp) {
			log.warn("AI analysis for complaint {} failed after {} attempts: {}", complaintId, attempts, describe(ex));
			return;
		}
		Duration delay = this.retryDelay.multipliedBy((long) Math.pow(4, attempts - 1));
		log.info("AI analysis for complaint {} failed ({}); retrying in {}s", complaintId, describe(ex),
				delay.toSeconds());
		schedule(complaintId, delay);
	}

	/** Failure summary for logs: type and status only, never payloads. */
	private static String describe(RuntimeException ex) {
		return (ex instanceof HttpStatusCodeException http)
				? ex.getClass().getSimpleName() + " " + http.getStatusCode().value() : ex.getClass().getSimpleName();
	}

	/** Why the analysis failed, in words an administrator can act on (shown on the complaint). */
	static String reason(RuntimeException ex) {
		return switch (ex) {
			case ResourceAccessException unreachable -> "The AI service could not be reached.";
			case HttpStatusCodeException http when http.getStatusCode().value() == 503 ->
				"The AI providers were unavailable.";
			case HttpStatusCodeException http ->
				"The AI service returned an error (HTTP " + http.getStatusCode().value() + ").";
			case UnusableAnalysis unusable -> "The AI answer did not pass validation.";
			case RestClientException unreadable -> "The AI service sent an answer that could not be read.";
			case DataAccessException database -> "The analysis could not be saved.";
			default -> "The analysis failed unexpectedly.";
		};
	}

	/** The model's answer referenced ids we did not offer, lacked a field, or had the wrong vector size. */
	static final class UnusableAnalysis extends RuntimeException {

		UnusableAnalysis() {
			super("unusable analysis");
		}

	}

	private static String vector(float[] values) {
		StringBuilder text = new StringBuilder("[");
		for (int i = 0; i < values.length; i++) {
			text.append(i == 0 ? "" : ",").append(values[i]);
		}
		return text.append(']').toString();
	}

	private static String truncate(String value, int max) {
		return (value == null || value.length() <= max) ? value : value.substring(0, max);
	}

	@PreDestroy
	void stop() {
		this.executor.shutdownNow();
	}

}
