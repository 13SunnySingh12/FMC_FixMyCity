package com.fixmycity.complaint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fixmycity.auth.AuthUser;
import com.fixmycity.common.ApiException;
import com.fixmycity.common.PageResponse;
import com.fixmycity.common.Text;
import com.fixmycity.complaint.ComplaintDetail.AiAnalysis;
import com.fixmycity.complaint.ComplaintDetail.FeedbackView;
import com.fixmycity.complaint.ComplaintDetail.Image;
import com.fixmycity.complaint.ComplaintDetail.Note;
import com.fixmycity.complaint.ComplaintDetail.Person;
import com.fixmycity.complaint.ComplaintDetail.TimelineEntry;
import com.fixmycity.department.Department;
import com.fixmycity.department.DepartmentService;
import com.fixmycity.storage.ImageFile;
import com.fixmycity.storage.StorageService;
import com.fixmycity.user.Role;
import com.fixmycity.user.User;
import com.fixmycity.user.UserRepository;
import jakarta.persistence.criteria.Predicate;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** The complaint lifecycle for citizens, officers and admins. Every rule is enforced here, whatever the UI shows. */
@Service
public class ComplaintService {

	static final int MAX_PROOFS = 5;

	public record Submission(ComplaintDetail complaint, boolean created) {
	}

	private final ComplaintRepository complaints;

	private final StatusHistoryRepository history;

	private final ComplaintNoteRepository notes;

	private final AttachmentRepository attachments;

	private final FeedbackRepository feedback;

	private final UserRepository users;

	private final DepartmentService departments;

	private final StorageService storage;

	private final ApplicationEventPublisher events;

	ComplaintService(ComplaintRepository complaints, StatusHistoryRepository history, ComplaintNoteRepository notes,
			AttachmentRepository attachments, FeedbackRepository feedback, UserRepository users,
			DepartmentService departments, StorageService storage, ApplicationEventPublisher events) {
		this.complaints = complaints;
		this.history = history;
		this.notes = notes;
		this.attachments = attachments;
		this.feedback = feedback;
		this.users = users;
		this.departments = departments;
		this.storage = storage;
		this.events = events;
	}

	// Citizen

	@Transactional
	public Submission submit(AuthUser actor, String title, String description, String location, Long categoryId,
			UUID requestId, MultipartFile image) {
		UUID key = (requestId != null) ? requestId : UUID.randomUUID();
		Optional<Complaint> retried = this.complaints.findByCitizenIdAndRequestId(actor.id(), key);
		if (retried.isPresent()) {
			return new Submission(detail(retried.get(), actor), false);
		}
		ImageFile file = (image == null || image.isEmpty()) ? null : ImageFile.of(image);
		User citizen = this.users.getReferenceById(actor.id());
		Complaint complaint = this.complaints.save(new Complaint(citizen, key, title.strip(), description.strip(),
				location.strip(), this.departments.category(categoryId)));
		record(complaint, "Complaint submitted", citizen);
		if (file != null) {
			store(complaint, Attachment.Kind.COMPLAINT_IMAGE, file, citizen);
		}
		this.events.publishEvent(new ComplaintSubmitted(complaint.getId()));
		return new Submission(detail(complaint, actor), true);
	}

	@Transactional
	public ComplaintDetail reopen(Long id, AuthUser actor, String reason) {
		Complaint complaint = visible(id, actor);
		complaint.reopen();
		User citizen = this.users.getReferenceById(actor.id());
		record(complaint, "Reopened by citizen: " + reason.strip(), citizen);
		User officer = complaint.getAssignedOfficer();
		if (!officer.isActive()) {
			complaint.moveTo(officer.getDepartment());
			record(complaint, "Moved to " + officer.getDepartment().getName()
					+ "; awaiting officer assignment because the previous officer is no longer active", citizen);
		}
		return detail(complaint, actor);
	}

	@Transactional
	public ComplaintDetail giveFeedback(Long id, AuthUser actor, int rating, String comment) {
		Complaint complaint = visible(id, actor);
		complaint.close();
		this.feedback.save(new Feedback(complaint.getId(), (short) rating, Text.blankToNull(comment)));
		record(complaint, "Closed after citizen feedback (" + rating + "/5)", this.users.getReferenceById(actor.id()));
		return detail(complaint, actor);
	}

	// Officer

	@Transactional
	public ComplaintDetail start(Long id, AuthUser actor) {
		Complaint complaint = visible(id, actor);
		complaint.start();
		record(complaint, "Work started", this.users.getReferenceById(actor.id()));
		return detail(complaint, actor);
	}

	@Transactional
	public ComplaintDetail addNote(Long id, AuthUser actor, String body) {
		Complaint complaint = visible(id, actor);
		complaint.requireStatus("Notes can only be added while the complaint is being worked on.",
				ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS);
		this.notes.save(new ComplaintNote(complaint, this.users.getReferenceById(actor.id()), body.strip()));
		complaint.touch(); // new evidence counts as an update for the citizen
		return detail(complaint, actor);
	}

	@Transactional
	public ComplaintDetail addProofs(Long id, AuthUser actor, List<MultipartFile> files) {
		Complaint complaint = visible(id, actor);
		complaint.requireStatus("Resolution proof can be uploaded once work is in progress.",
				ComplaintStatus.IN_PROGRESS);
		if (files == null || files.isEmpty()) {
			throw ApiException.badRequest("Attach at least one image.");
		}
		List<ImageFile> images = files.stream().map(ImageFile::of).toList();
		if (images.size() > round(complaint.getId()).proofsRemaining()) {
			throw ApiException.badRequest(
					"A round of work can take at most " + MAX_PROOFS + " resolution-proof images.");
		}
		User officer = this.users.getReferenceById(actor.id());
		images.forEach((image) -> store(complaint, Attachment.Kind.RESOLUTION_PROOF, image, officer));
		complaint.touch();
		return detail(complaint, actor);
	}

	@Transactional
	public ComplaintDetail resolve(Long id, AuthUser actor, String note) {
		Complaint complaint = visible(id, actor);
		complaint.requireStatus("Start work on the complaint before resolving it.", ComplaintStatus.IN_PROGRESS);
		if (!round(complaint.getId()).hasEvidence()) {
			throw ApiException.conflict(
					"Add an investigation note and upload resolution proof for this round of work before resolving.");
		}
		complaint.resolve();
		String resolution = Text.blankToNull(note);
		record(complaint, resolution == null ? "Marked resolved" : "Marked resolved: " + resolution,
				this.users.getReferenceById(actor.id()));
		return detail(complaint, actor);
	}

	// Admin, or the currently assigned officer

	@Transactional
	public ComplaintDetail assign(Long id, AuthUser actor, Long officerId, Long departmentId, String note) {
		if ((officerId == null) == (departmentId == null)) {
			throw ApiException.badRequest("Choose either an officer or a department.");
		}
		Complaint complaint = visible(id, actor);
		User previous = complaint.getAssignedOfficer();
		String entry;
		if (officerId != null) {
			User officer = this.users.findById(officerId)
				.filter((user) -> user.getRole() == Role.OFFICER && user.isActive())
				.orElseThrow(() -> ApiException.badRequest("Choose an active officer."));
			if (previous != null && previous.getId().equals(officer.getId())) {
				throw ApiException.badRequest("The complaint is already assigned to this officer.");
			}
			complaint.assignTo(officer);
			entry = ((previous == null) ? "Assigned to " : "Reassigned from " + previous.getName() + " to ")
					+ officer.getName() + " (" + officer.getDepartment().getName() + ")";
		}
		else {
			Department department = this.departments.department(departmentId);
			Department current = complaint.getDepartment();
			if (previous == null && current != null && current.getId().equals(department.getId())) {
				throw ApiException.badRequest("The complaint is already waiting in this department's queue.");
			}
			complaint.moveTo(department);
			entry = "Moved to " + department.getName() + "; awaiting officer assignment";
		}
		String reason = Text.blankToNull(note);
		record(complaint, (reason == null) ? entry : entry + ". Reason: " + reason, this.users.getReferenceById(actor.id()));
		return detail(complaint, actor);
	}

	// Admin

	@Transactional
	public ComplaintDetail update(Long id, AuthUser actor, Long categoryId, Priority priority) {
		Complaint complaint = visible(id, actor);
		complaint.requireStatus("Closed complaints cannot be changed.", ComplaintStatus.SUBMITTED,
				ComplaintStatus.ASSIGNED, ComplaintStatus.IN_PROGRESS, ComplaintStatus.RESOLVED);
		if (categoryId != null) {
			complaint.setCategory(this.departments.category(categoryId));
		}
		if (priority != null) {
			complaint.setPriority(priority);
		}
		return detail(complaint, actor);
	}

	@Transactional
	public ComplaintDetail close(Long id, AuthUser actor, String note) {
		Complaint complaint = visible(id, actor);
		complaint.close();
		String reason = Text.blankToNull(note);
		record(complaint, (reason == null) ? "Closed by administrator" : "Closed by administrator: " + reason,
				this.users.getReferenceById(actor.id()));
		return detail(complaint, actor);
	}

	// Reads

	@Transactional(readOnly = true)
	public ComplaintDetail get(Long id, AuthUser actor) {
		return detail(visible(id, actor), actor);
	}

	@Transactional(readOnly = true)
	public PageResponse<ComplaintSummary> list(AuthUser actor, ComplaintStatus status, Long categoryId,
			Long departmentId, Priority priority, int page, int size) {
		Specification<Complaint> scope = (root, query, cb) -> {
			List<Predicate> where = new ArrayList<>(List.of(visibleTo(actor).toPredicate(root, query, cb)));
			if (status != null) {
				where.add(cb.equal(root.get("status"), status));
			}
			if (categoryId != null) {
				where.add(cb.equal(root.get("category").get("id"), categoryId));
			}
			if (departmentId != null) {
				where.add(cb.equal(root.get("department").get("id"), departmentId));
			}
			if (priority != null) {
				where.add(cb.equal(root.get("priority"), priority));
			}
			return cb.and(where.toArray(Predicate[]::new));
		};
		var request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
		return PageResponse.of(this.complaints.findAll(scope, request), ComplaintSummary::from);
	}

	/** Summaries for the given ids that the caller may see (used to hydrate semantic search results). */
	@Transactional(readOnly = true)
	public Map<Long, ComplaintSummary> summaries(Collection<Long> ids, AuthUser actor) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		Specification<Complaint> scope = visibleTo(actor).and((root, query, cb) -> root.get("id").in(ids));
		return this.complaints.findAll(scope, Pageable.unpaged())
			.stream()
			.map(ComplaintSummary::from)
			.collect(Collectors.toMap(ComplaintSummary::id, Function.identity()));
	}

	/** The same visibility rule as {@link #visible}, as a query filter. */
	private static Specification<Complaint> visibleTo(AuthUser actor) {
		return (root, query, cb) -> switch (actor.role()) {
			case CITIZEN -> cb.equal(root.get("citizen").get("id"), actor.id());
			case OFFICER -> cb.equal(root.get("assignedOfficer").get("id"), actor.id());
			case ADMIN -> cb.conjunction();
		};
	}

	/** Complaints a user may not see behave as if they do not exist. */
	private Complaint visible(Long id, AuthUser actor) {
		Complaint complaint = this.complaints.findWithDetailsById(id)
			.orElseThrow(() -> ApiException.notFound("Complaint"));
		boolean allowed = switch (actor.role()) {
			case ADMIN -> true;
			case CITIZEN -> complaint.getCitizen().getId().equals(actor.id());
			case OFFICER -> assignedTo(complaint, actor);
		};
		if (!allowed) {
			throw ApiException.notFound("Complaint");
		}
		return complaint;
	}

	private static boolean assignedTo(Complaint complaint, AuthUser actor) {
		User officer = complaint.getAssignedOfficer();
		return officer != null && officer.getId().equals(actor.id());
	}

	private void record(Complaint complaint, String note, User actor) {
		this.history.save(new StatusHistory(complaint, note, actor));
	}

	private void store(Complaint complaint, Attachment.Kind kind, ImageFile image, User uploader) {
		String key = kind.newObjectKey(complaint.getId(), image.extension());
		this.storage.putInTransaction(key, image);
		this.attachments.save(new Attachment(complaint, kind, key, image, uploader));
	}

	/**
	 * The current round of work: everything since the latest (re)assignment. FMC requires officers to record their
	 * work before resolving, and both that evidence and the proof limit count per round, so a reopened or reassigned
	 * complaint needs new notes and proof and always has room for them.
	 */
	private record Round(boolean noted, int proofs) {

		static Round of(List<StatusHistory> timeline, List<ComplaintNote> notes, List<Attachment> files) {
			Instant start = timeline.stream()
				.filter((entry) -> entry.getStatus() == ComplaintStatus.ASSIGNED)
				.map(StatusHistory::getCreatedAt)
				.max(Comparator.naturalOrder())
				.orElse(Instant.EPOCH);
			boolean noted = notes.stream().anyMatch((note) -> !note.getCreatedAt().isBefore(start));
			long proofs = files.stream()
				.filter((file) -> file.getKind() == Attachment.Kind.RESOLUTION_PROOF
						&& !file.getCreatedAt().isBefore(start))
				.count();
			return new Round(noted, (int) proofs);
		}

		boolean hasEvidence() {
			return this.noted && this.proofs > 0;
		}

		int proofsRemaining() {
			return Math.max(0, MAX_PROOFS - this.proofs);
		}

	}

	private Round round(Long complaintId) {
		return Round.of(this.history.findByComplaintIdOrderByCreatedAtAscIdAsc(complaintId),
				this.notes.findByComplaintIdOrderByCreatedAtAscIdAsc(complaintId),
				this.attachments.findByComplaintIdOrderByIdAsc(complaintId));
	}

	private ComplaintDetail detail(Complaint c, AuthUser actor) {
		List<Attachment> files = this.attachments.findByComplaintIdOrderByIdAsc(c.getId());
		List<StatusHistory> entries = this.history.findByComplaintIdOrderByCreatedAtAscIdAsc(c.getId());
		List<ComplaintNote> written = this.notes.findByComplaintIdOrderByCreatedAtAscIdAsc(c.getId());
		Round round = Round.of(entries, written, files);
		List<Image> images = images(files, Attachment.Kind.COMPLAINT_IMAGE);
		List<Image> proofs = images(files, Attachment.Kind.RESOLUTION_PROOF);
		List<TimelineEntry> timeline = entries.stream()
			.map((h) -> new TimelineEntry(h.getStatus(), h.getNote(), h.getChangedBy().getName(),
					h.getChangedBy().getRole(), h.getCreatedAt()))
			.toList();
		List<Note> noteViews = written.stream()
			.map((n) -> new Note(n.getAuthor().getName(), n.getBody(), n.getCreatedAt()))
			.toList();
		FeedbackView feedbackView = this.feedback.findById(c.getId())
			.map((f) -> new FeedbackView(f.getRating(), f.getComment(), f.getCreatedAt()))
			.orElse(null);
		boolean staff = !actor.is(Role.CITIZEN);
		User citizen = c.getCitizen();
		User officer = c.getAssignedOfficer();
		Department department = c.getDepartment();
		return new ComplaintDetail(c.getId(), c.getTitle(), c.getDescription(), c.getLocation(),
				c.getCategory().getId(), c.getCategory().getName(), department == null ? null : department.getId(),
				department == null ? null : department.getName(), c.getStatus(), c.getPriority(),
				new Person(citizen.getId(), citizen.getName(), staff ? citizen.getPhone() : null),
				officer == null ? null : new Person(officer.getId(), officer.getName(), null), ai(c, actor), images,
				proofs, timeline, noteViews, feedbackView, actions(c, actor, round), round.proofsRemaining(),
				c.getCreatedAt(), c.getUpdatedAt());
	}

	private List<Image> images(List<Attachment> files, Attachment.Kind kind) {
		return files.stream()
			.filter((file) -> file.getKind() == kind)
			.map((file) -> new Image(file.getId(), this.storage.signedUrl(file.getObjectKey()), file.getOriginalName(),
					file.getCreatedAt()))
			.toList();
	}

	private static AiAnalysis ai(Complaint c, AuthUser actor) {
		var category = c.getAiCategory();
		var department = c.getAiDepartment();
		return new AiAnalysis(c.getAiStatus(), category == null ? null : category.getId(),
				category == null ? null : category.getName(), c.getAiPriority(),
				department == null ? null : department.getId(), department == null ? null : department.getName(),
				c.getAiSummary(), c.getAiImageFindings(), c.getAiModel(), c.getAiUpdatedAt(),
				actor.is(Role.ADMIN) ? c.getAiError() : null);
	}

	private static List<String> actions(Complaint c, AuthUser actor, Round round) {
		List<String> actions = new ArrayList<>();
		// An officer who has just handed the complaint over still receives it in the answer, but no longer holds it.
		if (actor.is(Role.OFFICER) && !assignedTo(c, actor)) {
			return actions;
		}
		ComplaintStatus status = c.getStatus();
		switch (actor.role()) {
			case CITIZEN -> {
				if (status == ComplaintStatus.RESOLVED) {
					actions.add("REOPEN");
					actions.add("FEEDBACK");
				}
			}
			case OFFICER -> {
				if (ComplaintStatus.ACTIVE_WORK.contains(status)) {
					actions.add("ADD_NOTE");
					actions.add("REASSIGN");
				}
				if (status == ComplaintStatus.ASSIGNED) {
					actions.add("START");
				}
				if (status == ComplaintStatus.IN_PROGRESS) {
					if (round.proofsRemaining() > 0) {
						actions.add("UPLOAD_PROOF");
					}
					if (round.hasEvidence()) {
						actions.add("RESOLVE");
					}
				}
			}
			case ADMIN -> {
				if (status != ComplaintStatus.RESOLVED && status != ComplaintStatus.CLOSED) {
					actions.add("ASSIGN");
				}
				if (status != ComplaintStatus.CLOSED) {
					actions.add("EDIT");
				}
				if (status == ComplaintStatus.RESOLVED) {
					actions.add("CLOSE");
				}
				if (c.getAiStatus() == Complaint.AiStatus.FAILED) {
					actions.add("RETRY_AI");
				}
			}
		}
		return actions;
	}

}
