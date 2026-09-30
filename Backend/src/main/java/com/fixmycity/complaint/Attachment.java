package com.fixmycity.complaint;

import java.time.Instant;
import java.util.UUID;

import com.fixmycity.storage.ImageFile;
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

/** An image stored in B2; only its object key lives in the database. */
@Entity
@Table(name = "complaint_attachments")
public class Attachment {

	/** FMC's object-key layout: {prefix}/{complaintId}/{fileName}. */
	public enum Kind {

		COMPLAINT_IMAGE("complaints"), RESOLUTION_PROOF("resolution-proofs");

		private final String prefix;

		Kind(String prefix) {
			this.prefix = prefix;
		}

		String newObjectKey(long complaintId, String extension) {
			return "%s/%d/%s.%s".formatted(this.prefix, complaintId, UUID.randomUUID(), extension);
		}

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "complaint_id")
	private Complaint complaint;

	@Enumerated(EnumType.STRING)
	private Kind kind;

	@Column(name = "object_key", nullable = false, updatable = false)
	private String objectKey;

	@Column(name = "original_name")
	private String originalName;

	@Column(name = "content_type")
	private String contentType;

	@Column(name = "size_bytes")
	private long sizeBytes;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "uploaded_by")
	private User uploadedBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	protected Attachment() {
	}

	Attachment(Complaint complaint, Kind kind, String objectKey, ImageFile image, User uploadedBy) {
		this.complaint = complaint;
		this.kind = kind;
		this.objectKey = objectKey;
		this.originalName = image.originalName();
		this.contentType = image.contentType();
		this.sizeBytes = image.bytes().length;
		this.uploadedBy = uploadedBy;
	}

	public Long getId() {
		return this.id;
	}

	public Kind getKind() {
		return this.kind;
	}

	public String getObjectKey() {
		return this.objectKey;
	}

	public String getOriginalName() {
		return this.originalName;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

}
