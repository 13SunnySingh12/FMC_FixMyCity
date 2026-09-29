package com.fixmycity.user;

import java.util.List;

import com.fixmycity.auth.AuthUser;
import com.fixmycity.common.PageResponse;
import com.fixmycity.user.UserAdminService.OfficerSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UserAdminController {

	record CreateOfficerRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Email @Size(max = 254) String email,
			@NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
			@Pattern(regexp = "^$|^[0-9+()\\- ]{7,20}$", message = "must be a valid phone number") String phone,
			@NotNull Long departmentId) {
	}

	record UpdateUserRequest(Boolean active, Long departmentId) {
	}

	private final UserAdminService service;

	UserAdminController(UserAdminService service) {
		this.service = service;
	}

	@GetMapping("/api/admin/users")
	PageResponse<UserResponse> users(@RequestParam(defaultValue = "CITIZEN") Role role,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return this.service.list(role, page, size);
	}

	@PostMapping("/api/admin/officers")
	@ResponseStatus(HttpStatus.CREATED)
	UserResponse createOfficer(@Valid @RequestBody CreateOfficerRequest request) {
		return this.service.createOfficer(request.name(), request.email(), request.password(), request.phone(),
				request.departmentId());
	}

	@PatchMapping("/api/admin/users/{id}")
	UserResponse update(@PathVariable Long id, @RequestBody UpdateUserRequest request,
			@AuthenticationPrincipal AuthUser actor) {
		return this.service.update(id, request.active(), request.departmentId(), actor.id());
	}

	@GetMapping("/api/officers")
	@PreAuthorize("hasAnyRole('ADMIN', 'OFFICER')")
	List<OfficerSummary> officers(@RequestParam(required = false) Long departmentId) {
		return this.service.officers(departmentId);
	}

}
