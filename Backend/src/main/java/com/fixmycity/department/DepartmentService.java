package com.fixmycity.department;

import java.util.List;

import com.fixmycity.common.ApiException;
import com.fixmycity.common.Text;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepartmentService {

	public record DepartmentResponse(Long id, String name, String description) {

		static DepartmentResponse from(Department department) {
			return new DepartmentResponse(department.getId(), department.getName(), department.getDescription());
		}

	}

	public record CategoryResponse(Long id, String name, String description, Long departmentId, String departmentName) {

		static CategoryResponse from(Category category) {
			Department department = category.getDepartment();
			return new CategoryResponse(category.getId(), category.getName(), category.getDescription(),
					department == null ? null : department.getId(), department == null ? null : department.getName());
		}

	}

	private final DepartmentRepository departments;

	private final CategoryRepository categories;

	private final ApplicationEventPublisher events;

	DepartmentService(DepartmentRepository departments, CategoryRepository categories,
			ApplicationEventPublisher events) {
		this.departments = departments;
		this.categories = categories;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public List<DepartmentResponse> departments() {
		return this.departments.findAllByOrderByIdAsc().stream().map(DepartmentResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public List<CategoryResponse> categories() {
		return this.categories.findAllByOrderByIdAsc().stream().map(CategoryResponse::from).toList();
	}

	public Department department(Long id) {
		return this.departments.findById(id).orElseThrow(() -> ApiException.notFound("Department"));
	}

	public Category category(Long id) {
		return this.categories.findById(id).orElseThrow(() -> ApiException.notFound("Category"));
	}

	@Transactional
	public DepartmentResponse saveDepartment(Long id, String name, String description) {
		if (this.departments.existsByNameIgnoreCaseAndIdNot(name.strip(), id == null ? -1 : id)) {
			throw ApiException.conflict("A department with this name already exists.");
		}
		Department department = (id == null) ? new Department(name.strip(), description) : department(id);
		department.setName(name.strip());
		department.setDescription(Text.blankToNull(description));
		this.events.publishEvent(new ReferenceDataChanged());
		return DepartmentResponse.from(this.departments.save(department));
	}

	@Transactional
	public CategoryResponse saveCategory(Long id, String name, String description, Long departmentId) {
		if (this.categories.existsByNameIgnoreCaseAndIdNot(name.strip(), id == null ? -1 : id)) {
			throw ApiException.conflict("A category with this name already exists.");
		}
		Department department = department(departmentId);
		Category category = (id == null) ? new Category(name.strip(), description, department) : category(id);
		category.setName(name.strip());
		category.setDescription(Text.blankToNull(description));
		category.setDepartment(department);
		this.events.publishEvent(new ReferenceDataChanged());
		return CategoryResponse.from(this.categories.save(category));
	}

	@Transactional
	public void deleteDepartment(Long id) {
		try {
			this.departments.delete(department(id));
			this.departments.flush();
			this.events.publishEvent(new ReferenceDataChanged());
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("This department is still used by categories, officers or complaints.");
		}
	}

	@Transactional
	public void deleteCategory(Long id) {
		try {
			this.categories.delete(category(id));
			this.categories.flush();
			this.events.publishEvent(new ReferenceDataChanged());
		}
		catch (DataIntegrityViolationException ex) {
			throw ApiException.conflict("This category is still used by complaints.");
		}
	}

}
