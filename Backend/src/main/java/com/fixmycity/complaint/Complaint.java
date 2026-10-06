package com.fixmycity.complaint;

import java.time.Instant;
import java.util.UUID;

import com.fixmycity.common.ApiException;
import com.fixmycity.department.Category;
import com.fixmycity.department.Department;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A civic complaint. Status changes go through the methods below so FMC's lifecycle rules live in one place.
 * AI analysis fields are read-only here: only the analysis worker writes them, with targeted SQL. Dynamic updates
 * mean a user action never rewrites columns it did not change, such as a priority the AI filled in meanwhile.
 */
@Entity
@Table(name = "complaints")
@DynamicUpdate
public class Complaint {

	public enum AiStatus {

		PENDING, PROCESSING, COMPLETED, FAILED

	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "citizen_id")
	private User citizen;

	@Column(name = "request_id", nullable = false, updatable = false)
	private UUID requestId;

	private String title;

	private String description;

	private String location;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "category_id")
	private Category category;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "department_id")
	private Department department;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assigned_officer_id")
	private User assignedOfficer;

	@Enumerated(EnumType.STRING)
	private ComplaintStatus status = ComplaintStatus.SUBMITTED;

	@Enumerated(EnumType.STRING)
	private Priority priority;

	@Enumerated(EnumType.STRING)
	@Column(name = "ai_status", insertable = false, updatable = false)
	private AiStatus aiStatus = AiStatus.PENDING;

	@Column(name = "ai_error", insertable = false, updatable = false)
	private String aiError;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ai_category_id", insertable = false, updatable = false)
	private Category aiCategory;

	@Enumerated(EnumType.STRING)
	@Column(name = "ai_priority", insertable = false, updatable = false)
	private Priority aiPriority;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ai_department_id", insertable = false, updatable = false)
	private Department aiDepartment;

	@Column(name = "ai_summary", insertable = false, updatable = false)
	private String aiSummary;

	@Column(name = "ai_image_findings", insertable = false, updatable = false)
	private String aiImageFindings;

	@Column(name = "ai_model", insertable = false, updatable = false)
	private String aiModel;

	@Column(name = "ai_updated_at", insertable = false, updatable = false)
	private Instant aiUpdatedAt;

	@Version
	private long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt = Instant.now();

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt = Instant.now();

	protected Complaint() {
	}

	public Complaint(User citizen, UUID requestId, String title, String description, String location,
			Category category) {
		this.citizen = citizen;
		this.requestId = requestId;
		this.title = title;
		this.description = description;
		this.location = location;
		this.category = category;
		this.department = category.getDepartment();
	}

	@PreUpdate
	void touch() {
		this.updatedAt = Instant.now();
	}

	void assignTo(User officer) {
		requireStatus("Only open complaints can be assigned.", ComplaintStatus.SUBMITTED, ComplaintStatus.ASSIGNED,
				ComplaintStatus.IN_PROGRESS);
		this.assignedOfficer = officer;
		this.department = officer.getDepartment();
		this.status = ComplaintStatus.ASSIGNED;
	}

	/** Routes the complaint to a department without an officer; it waits there for assignment. */
	void moveTo(Department department) {
		requireStatus("Only open complaints can be moved.", ComplaintStatus.SUBMITTED, ComplaintStatus.ASSIGNED,
				ComplaintStatus.IN_PROGRESS);
		this.assignedOfficer = null;
		this.department = department;
		this.status = ComplaintStatus.SUBMITTED;
	}

	void start() {
		requireStatus("Only assigned complaints can be started.", ComplaintStatus.ASSIGNED);
		this.status = ComplaintStatus.IN_PROGRESS;
	}

	void resolve() {
		requireStatus("Start work on the complaint before resolving it.", ComplaintStatus.IN_PROGRESS);
		this.status = ComplaintStatus.RESOLVED;
	}

	/** A citizen reopens an unfixed complaint; it goes back to the officer who resolved it. */
	void reopen() {
		requireStatus("Only resolved complaints can be reopened.", ComplaintStatus.RESOLVED);
		this.status = ComplaintStatus.ASSIGNED;
		this.department = this.assignedOfficer.getDepartment(); // the officer may have changed department since
	}

	void close() {
		requireStatus("Only resolved complaints can be closed.", ComplaintStatus.RESOLVED);
		this.status = ComplaintStatus.CLOSED;
	}

	void requireStatus(String message, ComplaintStatus... allowed) {
		for (ComplaintStatus candidate : allowed) {
			if (this.status == candidate) {
				return;
			}
		}
		throw ApiException.conflict(message);
	}

	public Long getId() {
		return this.id;
	}

	public User getCitizen() {
		return this.citizen;
	}

	public String getTitle() {
		return this.title;
	}

	public String getDescription() {
		return this.description;
	}

	public String getLocation() {
		return this.location;
	}

	public Category getCategory() {
		return this.category;
	}

	void setCategory(Category category) {
		this.category = category;
	}

	public Department getDepartment() {
		return this.department;
	}

	public User getAssignedOfficer() {
		return this.assignedOfficer;
	}

	public ComplaintStatus getStatus() {
		return this.status;
	}

	public Priority getPriority() {
		return this.priority;
	}

	void setPriority(Priority priority) {
		this.priority = priority;
	}

	public AiStatus getAiStatus() {
		return this.aiStatus;
	}

	public String getAiError() {
		return this.aiError;
	}

	public Category getAiCategory() {
		return this.aiCategory;
	}

	public Priority getAiPriority() {
		return this.aiPriority;
	}

	public Department getAiDepartment() {
		return this.aiDepartment;
	}

	public String getAiSummary() {
		return this.aiSummary;
	}

	public String getAiImageFindings() {
		return this.aiImageFindings;
	}

	public String getAiModel() {
		return this.aiModel;
	}

	public Instant getAiUpdatedAt() {
		return this.aiUpdatedAt;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

}
