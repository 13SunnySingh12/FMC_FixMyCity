package com.fixmycity;

import com.fixmycity.TestApi.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@IntegrationTest
class AdminManagementIntegrationTest {

	@Autowired
	MockMvc mvc;

	TestApi api;

	Session admin;

	@BeforeEach
	void setUp() throws Exception {
		this.api = new TestApi(this.mvc);
		this.admin = this.api.admin();
	}

	@Test
	void referenceDataIsReadableByAnyUserButOnlyAdminsChangeIt() throws Exception {
		Session citizen = this.api.citizen();
		this.api.get("/api/categories", citizen)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].name", hasItem("Streetlights")))
			.andExpect(jsonPath("$[?(@.name == 'Streetlights')].departmentName").value("Street Lighting & Electrical"));
		this.api.post("/api/admin/departments", citizen, "{\"name\": \"Parks\"}").andExpect(status().isForbidden());
		this.api.get("/api/categories", null).andExpect(status().isUnauthorized());
	}

	@Test
	void adminManagesDepartmentsAndCategoryRouting() throws Exception {
		String name = TestApi.unique("Parks");
		long department = TestApi.id(this.api.post("/api/admin/departments", this.admin,
				"{\"name\": \"%s\", \"description\": \"Public parks\"}".formatted(name))
			.andExpect(status().isCreated()));
		long category = TestApi.id(this.api.post("/api/admin/categories", this.admin,
				"{\"name\": \"%s\", \"departmentId\": %d}".formatted(TestApi.unique("Park damage"), department))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.departmentName").value(name)));

		this.api.put("/api/admin/departments/" + department, this.admin, "{\"name\": \"%s\"}".formatted(name + " & Gardens"))
			.andExpect(status().isOk());
		this.api.post("/api/admin/departments", this.admin, "{\"name\": \"water supply\"}")
			.andExpect(status().isConflict());
		this.api.delete("/api/admin/departments/" + department, this.admin)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.detail").value("This department is still used by categories, officers or complaints."));
		this.api.delete("/api/admin/categories/" + category, this.admin).andExpect(status().isNoContent());
		this.api.delete("/api/admin/departments/" + department, this.admin).andExpect(status().isNoContent());
	}

	@Test
	void adminCreatesOfficersWhoCanLogInAndAppearInPickers() throws Exception {
		long waterSupply = this.api.departmentId("Water Supply");
		Session officer = this.api.officer(waterSupply);

		this.api.get("/api/auth/me", officer)
			.andExpect(jsonPath("$.role").value("OFFICER"))
			.andExpect(jsonPath("$.departmentName").value("Water Supply"));
		this.api.get("/api/officers?departmentId=" + waterSupply, officer)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[*].id", hasItem((int) officer.id())))
			.andExpect(jsonPath("$[0].email").isNotEmpty()); // tells namesakes apart in the picker
		this.api.get("/api/officers", this.api.citizen()).andExpect(status().isForbidden());
	}

	@Test
	void officerMustBelongToAnExistingDepartment() throws Exception {
		String body = "{\"name\": \"No Dept\", \"email\": \"%s\", \"password\": \"%s\"%s}";
		this.api.post("/api/admin/officers", this.admin, body.formatted(TestApi.uniqueEmail(), TestApi.PASSWORD, ""))
			.andExpect(status().isBadRequest());
		this.api.post("/api/admin/officers", this.admin,
				body.formatted(TestApi.uniqueEmail(), TestApi.PASSWORD, ", \"departmentId\": 999999"))
			.andExpect(status().isNotFound());
	}

	@Test
	void adminDeactivatesAndReactivatesCitizens() throws Exception {
		Session citizen = this.api.citizen();
		this.api.patch("/api/admin/users/" + citizen.id(), this.admin, "{\"active\": false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.active").value(false));
		this.api.get("/api/auth/me", citizen).andExpect(status().isUnauthorized());

		this.api.patch("/api/admin/users/" + citizen.id(), this.admin, "{\"active\": true}").andExpect(status().isOk());
		this.api.get("/api/auth/me", citizen).andExpect(status().isOk());
	}

	@Test
	void guardRailsOnAccountChanges() throws Exception {
		this.api.patch("/api/admin/users/" + this.admin.id(), this.admin, "{\"active\": false}")
			.andExpect(status().isBadRequest());
		this.api.patch("/api/admin/users/" + this.api.citizen().id(), this.admin, "{\"departmentId\": 1}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void listsAccountsByRoleWithPaging() throws Exception {
		this.api.officer(this.api.departmentId("Roads & Public Works"));
		this.api.get("/api/admin/users?role=OFFICER&size=1", this.admin)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items", hasSize(1)))
			.andExpect(jsonPath("$.items[0].role").value("OFFICER"))
			.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
		this.api.get("/api/admin/users?size=0", this.admin).andExpect(status().isBadRequest());
	}

}
