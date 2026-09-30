package com.fixmycity.complaint;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The citizen's feedback on a resolution; at most one per complaint (primary key = complaint id). */
@Entity
@Table(name = "complaint_feedback")
public class Feedback {

	@Id
	@Column(name = "complaint_id")
	private Long complaintId;

	private short rating;

	private String comment;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected Feedback() {
	}

	Feedback(Long complaintId, short rating, String comment) {
		this.complaintId = complaintId;
		this.rating = rating;
		this.comment = comment;
	}

	public short getRating() {
		return this.rating;
	}

	public String getComment() {
		return this.comment;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
