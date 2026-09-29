package com.fixmycity.auth;

import com.fixmycity.user.Role;

/** The authenticated caller, reloaded from the database on every request. */
public record AuthUser(Long id, Role role, Long departmentId) {

	public boolean is(Role role) {
		return this.role == role;
	}

}
