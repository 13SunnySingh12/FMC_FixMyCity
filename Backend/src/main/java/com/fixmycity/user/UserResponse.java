package com.fixmycity.user;

import java.time.Instant;

/** Public view of a user; never exposes the password hash. */
public record UserResponse(Long id, String name, String email, Role role, String phone, Long departmentId,
		String departmentName, boolean active, Instant createdAt) {

	public static UserResponse from(User user) {
		var department = user.getDepartment();
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getRole(), user.getPhone(),
				department == null ? null : department.getId(), department == null ? null : department.getName(),
				user.isActive(), user.getCreatedAt());
	}

}
