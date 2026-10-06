package com.fixmycity.ai;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fixmycity.ai.AiClient.Answer;
import com.fixmycity.ai.AiClient.ComplaintHit;
import com.fixmycity.ai.AiClient.KnowledgeHit;
import com.fixmycity.ai.AiClient.Suggestion;
import com.fixmycity.auth.AuthUser;
import com.fixmycity.common.ApiException;
import com.fixmycity.common.RateLimit;
import com.fixmycity.common.Text;
import com.fixmycity.complaint.ComplaintRepository;
import com.fixmycity.complaint.ComplaintService;
import com.fixmycity.complaint.ComplaintSummary;
import com.fixmycity.user.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Writing assistant, civic assistant and semantic search, proxied to the AI service with the caller's scope. */
@RestController
class AiController {

	record WriteRequest(@Size(max = 150) String title, @NotBlank @Size(min = 10, max = 5000) String description,
			@Size(max = 300) String location) {

		// Lengths are checked on the text the AI service receives, not on surrounding whitespace.
		WriteRequest {
			title = Text.blankToNull(title);
			description = Text.blankToNull(description);
			location = Text.blankToNull(location);
		}

	}

	record AskRequest(@NotBlank @Size(min = 3, max = 500) String question) {

		AskRequest {
			question = Text.blankToNull(question);
		}

	}

	record ComplaintResult(ComplaintSummary complaint, double score) {
	}

	private final AiClient ai;

	private final AiJobs jobs;

	private final ComplaintService complaints;

	private final ComplaintRepository complaintRepository;

	private final RateLimit rateLimit = new RateLimit(20, Duration.ofMinutes(1),
			"Too many AI requests. Please wait a minute and try again.");

	AiController(AiClient ai, AiJobs jobs, ComplaintService complaints, ComplaintRepository complaintRepository) {
		this.ai = ai;
		this.jobs = jobs;
		this.complaints = complaints;
		this.complaintRepository = complaintRepository;
	}

	/** Search text is trimmed before its length is checked, like the request bodies above. */
	@InitBinder
	void trimParameters(WebDataBinder binder) {
		binder.registerCustomEditor(String.class, new StringTrimmerEditor(false));
	}

	@PostMapping("/api/ai/write")
	@PreAuthorize("hasRole('CITIZEN')")
	Suggestion write(@Valid @RequestBody WriteRequest request, @AuthenticationPrincipal AuthUser user) {
		this.rateLimit.check(user.id());
		return this.ai.improve(new AiClient.WriteRequest(orEmpty(request.title()), request.description(),
				orEmpty(request.location())));
	}

	@PostMapping("/api/assistant/ask")
	Answer ask(@Valid @RequestBody AskRequest request, @AuthenticationPrincipal AuthUser user) {
		this.rateLimit.check(user.id());
		return this.ai.ask(request.question());
	}

	@GetMapping("/api/search/complaints")
	List<ComplaintResult> searchComplaints(@RequestParam @NotBlank @Size(min = 2, max = 300) String q,
			@AuthenticationPrincipal AuthUser user) {
		this.rateLimit.check(user.id());
		List<ComplaintHit> hits = this.ai.searchComplaints(q, user.is(Role.CITIZEN) ? user.id() : null,
				user.is(Role.OFFICER) ? user.id() : null, 20);
		// The AI service already filtered by scope; the backend re-checks visibility before returning anything.
		Map<Long, ComplaintSummary> visible = this.complaints
			.summaries(hits.stream().map(ComplaintHit::complaintId).toList(), user);
		return hits.stream()
			.filter((hit) -> visible.containsKey(hit.complaintId()))
			.map((hit) -> new ComplaintResult(visible.get(hit.complaintId()), hit.score()))
			.toList();
	}

	@GetMapping("/api/search/knowledge")
	List<KnowledgeHit> searchKnowledge(@RequestParam @NotBlank @Size(min = 2, max = 300) String q,
			@AuthenticationPrincipal AuthUser user) {
		this.rateLimit.check(user.id());
		return this.ai.searchKnowledge(q, 5);
	}

	@PostMapping("/api/complaints/{id}/analysis/retry")
	@PreAuthorize("hasRole('ADMIN')")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void retryAnalysis(@PathVariable Long id) {
		if (!this.complaintRepository.existsById(id)) {
			throw ApiException.notFound("Complaint");
		}
		this.jobs.retry(id);
	}

	private static String orEmpty(String value) {
		return (value == null) ? "" : value;
	}

}
