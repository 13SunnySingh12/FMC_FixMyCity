package com.fixmycity.department;

import java.util.List;

import com.fixmycity.department.DepartmentService.CategoryResponse;
import com.fixmycity.department.DepartmentService.DepartmentResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reference data for every signed-in user; changes are admin-only (see SecurityConfig). */
@RestController
class DepartmentController {

	record DepartmentRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String description) {
	}

	record CategoryRequest(@NotBlank @Size(max = 80) String name, @Size(max = 500) String description,
			@NotNull Long departmentId) {
	}

	private final DepartmentService service;

	DepartmentController(DepartmentService service) {
		this.service = service;
	}

	@GetMapping("/api/departments")
	List<DepartmentResponse> departments() {
		return this.service.departments();
	}

	@GetMapping("/api/categories")
	List<CategoryResponse> categories() {
		return this.service.categories();
	}

	@PostMapping("/api/admin/departments")
	@ResponseStatus(HttpStatus.CREATED)
	DepartmentResponse createDepartment(@Valid @RequestBody DepartmentRequest request) {
		return this.service.saveDepartment(null, request.name(), request.description());
	}

	@PutMapping("/api/admin/departments/{id}")
	DepartmentResponse updateDepartment(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
		return this.service.saveDepartment(id, request.name(), request.description());
	}

	@DeleteMapping("/api/admin/departments/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteDepartment(@PathVariable Long id) {
		this.service.deleteDepartment(id);
	}

	@PostMapping("/api/admin/categories")
	@ResponseStatus(HttpStatus.CREATED)
	CategoryResponse createCategory(@Valid @RequestBody CategoryRequest request) {
		return this.service.saveCategory(null, request.name(), request.description(), request.departmentId());
	}

	@PutMapping("/api/admin/categories/{id}")
	CategoryResponse updateCategory(@PathVariable Long id, @Valid @RequestBody CategoryRequest request) {
		return this.service.saveCategory(id, request.name(), request.description(), request.departmentId());
	}

	@DeleteMapping("/api/admin/categories/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteCategory(@PathVariable Long id) {
		this.service.deleteCategory(id);
	}

}
