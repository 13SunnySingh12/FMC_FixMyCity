package com.fixmycity.department;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

	List<Department> findAllByOrderByIdAsc();

	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

}
