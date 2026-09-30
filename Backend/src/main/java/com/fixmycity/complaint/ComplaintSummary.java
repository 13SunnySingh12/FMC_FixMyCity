package com.fixmycity.complaint;

import java.time.Instant;

/** A row in complaint lists (citizen history, officer dashboard, admin management). */
public record ComplaintSummary(Long id, String title, String location, Long categoryId, String categoryName,
		Long departmentId, String departmentName, ComplaintStatus status, Priority priority,
		Complaint.AiStatus aiStatus, String aiSummary, String citizenName, Long assignedOfficerId,
		String assignedOfficerName, Instant createdAt, Instant updatedAt) {

	static ComplaintSummary from(Complaint c) {
		var department = c.getDepartment();
		var officer = c.getAssignedOfficer();
		return new ComplaintSummary(c.getId(), c.getTitle(), c.getLocation(), c.getCategory().getId(),
				c.getCategory().getName(), department == null ? null : department.getId(),
				department == null ? null : department.getName(), c.getStatus(), c.getPriority(), c.getAiStatus(),
				c.getAiSummary(), c.getCitizen().getName(), officer == null ? null : officer.getId(),
				officer == null ? null : officer.getName(), c.getCreatedAt(), c.getUpdatedAt());
	}

}
