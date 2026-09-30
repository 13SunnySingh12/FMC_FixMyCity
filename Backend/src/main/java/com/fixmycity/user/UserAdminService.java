package com.fixmycity.user;

import java.util.List;

import com.fixmycity.auth.AuthService;
import com.fixmycity.common.ApiException;
import com.fixmycity.common.PageResponse;
import com.fixmycity.common.Text;
import com.fixmycity.complaint.ComplaintRepository;
import com.fixmycity.complaint.ComplaintStatus;
import com.fixmycity.department.DepartmentService;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin management of citizen and officer accounts (FMC features 30 and 31). */
@Service
public class UserAdminService {

	public record OfficerSummary(Long id, String name, Long departmentId, String departmentName) {

		static OfficerSummary from(User user) {
			return new OfficerSummary(user.getId(), user.getName(), user.getDepartment().getId(),
					user.getDepartment().getName());
		}

	}

	private final UserRepository users;

	private final AuthService auth;

	private final DepartmentService departments;

	private final ComplaintRepository complaints;

	UserAdminService(UserRepository users, AuthService auth, DepartmentService departments,
			ComplaintRepository complaints) {
		this.users = users;
		this.auth = auth;
		this.departments = departments;
		this.complaints = complaints;
	}

	@Transactional(readOnly = true)
	public PageResponse<UserResponse> list(Role role, int page, int size) {
		var request = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
		return PageResponse.of(this.users.findByRole(role, request), UserResponse::from);
	}

	@Transactional
	public UserResponse createOfficer(String name, String email, String password, String phone, Long departmentId) {
		if (this.users.existsByEmail(User.normalizeEmail(email))) {
			throw ApiException.conflict("An account with this email already exists.");
		}
		User officer = new User(name.strip(), email, this.auth.hashPassword(password), Role.OFFICER);
		officer.setPhone(Text.blankToNull(phone));
		officer.setDepartment(this.departments.department(departmentId));
		return UserResponse.from(this.users.save(officer));
	}

	@Transactional
	public UserResponse update(Long id, Boolean active, Long departmentId, Long actorId) {
		User user = this.users.findById(id).orElseThrow(() -> ApiException.notFound("User"));
		if (active != null) {
			if (!active && user.getId().equals(actorId)) {
				throw ApiException.badRequest("You cannot deactivate your own account.");
			}
			if (!active) {
				requireNoOpenWork(user);
			}
			user.setActive(active);
		}
		if (departmentId != null) {
			if (user.getRole() != Role.OFFICER) {
				throw ApiException.badRequest("Only officers belong to a department.");
			}
			if (!departmentId.equals(user.getDepartment().getId())) {
				requireNoOpenWork(user);
			}
			user.setDepartment(this.departments.department(departmentId));
		}
		return UserResponse.from(user);
	}

	/** An officer's active complaints must be reassigned first, or they would be left without an owner. */
	private void requireNoOpenWork(User user) {
		if (user.getRole() == Role.OFFICER
				&& this.complaints.existsByAssignedOfficerIdAndStatusIn(user.getId(), ComplaintStatus.ACTIVE_WORK)) {
			throw ApiException.conflict("Reassign this officer's open complaints first.");
		}
	}

	/** Active officers, for assignment and reassignment pickers. */
	@Transactional(readOnly = true)
	public List<OfficerSummary> officers(Long departmentId) {
		List<User> officers = (departmentId == null)
				? this.users.findByRoleAndActiveTrueOrderByNameAsc(Role.OFFICER)
				: this.users.findByRoleAndActiveTrueAndDepartmentIdOrderByNameAsc(Role.OFFICER, departmentId);
		return officers.stream().map(OfficerSummary::from).toList();
	}

}
