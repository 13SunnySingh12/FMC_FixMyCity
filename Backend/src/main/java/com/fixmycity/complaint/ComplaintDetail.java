package com.fixmycity.complaint;

import java.time.Instant;
import java.util.List;

import com.fixmycity.user.Role;

/**
 * Everything a permitted user may see about one complaint. {@code actions} lists what the caller can do next,
 * computed from the same rules the server enforces.
 */
public record ComplaintDetail(Long id, String title, String description, String location, Long categoryId,
		String categoryName, Long departmentId, String departmentName, ComplaintStatus status, Priority priority,
		Person citizen, Person assignedOfficer, AiAnalysis ai, List<Image> images, List<Image> proofs,
		List<TimelineEntry> timeline, List<Note> notes, FeedbackView feedback, List<String> actions,
		Instant createdAt, Instant updatedAt) {

	public record Person(Long id, String name, String phone) {
	}

	public record AiAnalysis(Complaint.AiStatus status, Long categoryId, String categoryName, Priority priority,
			Long departmentId, String departmentName, String summary, String imageFindings, String model,
			Instant updatedAt, String error) {
	}

	public record Image(Long id, String url, String name, Instant uploadedAt) {
	}

	public record TimelineEntry(ComplaintStatus status, String note, String actorName, Role actorRole, Instant at) {
	}

	public record Note(String author, String body, Instant at) {
	}

	public record FeedbackView(int rating, String comment, Instant at) {
	}

}
