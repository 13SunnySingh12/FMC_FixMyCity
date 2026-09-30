package com.fixmycity.complaint;

import java.time.Instant;

import com.fixmycity.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One entry in a complaint's status timeline. */
@Entity
@Table(name = "complaint_status_history")
public class StatusHistory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "complaint_id")
	private Complaint complaint;

	@Enumerated(EnumType.STRING)
	private ComplaintStatus status;

	private String note;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "changed_by")
	private User changedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected StatusHistory() {
	}

	StatusHistory(Complaint complaint, String note, User changedBy) {
		this.complaint = complaint;
		this.status = complaint.getStatus();
		this.note = note;
		this.changedBy = changedBy;
	}

	public ComplaintStatus getStatus() {
		return this.status;
	}

	public String getNote() {
		return this.note;
	}

	public User getChangedBy() {
		return this.changedBy;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
