package com.fixmycity.department;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, Long> {

	@EntityGraph(attributePaths = "department")
	List<Category> findAllByOrderByIdAsc();

	boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

}
