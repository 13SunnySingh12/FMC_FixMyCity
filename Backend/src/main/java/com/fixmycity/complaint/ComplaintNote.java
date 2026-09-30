package com.fixmycity.complaint;

import java.time.Instant;

import com.fixmycity.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** An officer's investigation note. */
@Entity
@Table(name = "complaint_notes")
public class ComplaintNote {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "complaint_id")
	private Complaint complaint;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id")
	private User author;

	private String body;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected ComplaintNote() {
	}

	ComplaintNote(Complaint complaint, User author, String body) {
		this.complaint = complaint;
		this.author = author;
		this.body = body;
	}

	public User getAuthor() {
		return this.author;
	}

	public String getBody() {
		return this.body;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
