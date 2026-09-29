package com.fixmycity.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	boolean existsByRole(Role role);

	@EntityGraph(attributePaths = "department")
	Page<User> findByRole(Role role, Pageable pageable);

	@EntityGraph(attributePaths = "department")
	List<User> findByRoleAndActiveTrueOrderByNameAsc(Role role);

	@EntityGraph(attributePaths = "department")
	List<User> findByRoleAndActiveTrueAndDepartmentIdOrderByNameAsc(Role role, Long departmentId);

}
