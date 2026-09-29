package com.fixmycity;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@IntegrationTest
@Transactional
class SchemaMigrationTest {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void pgvectorAndAllTablesAreInPlace() {
		assertThat(jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname = 'vector'", String.class))
			.isNotBlank();
		assertThat(jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class))
			.contains("departments", "categories", "users", "complaints", "complaint_status_history",
					"complaint_notes", "complaint_attachments", "complaint_feedback", "knowledge_chunks");
		assertThat(jdbc.queryForList("""
				SELECT format_type(atttypid, atttypmod) FROM pg_attribute
				WHERE attname = 'embedding' AND attrelid IN ('complaints'::regclass, 'knowledge_chunks'::regclass)
				""", String.class))
			.containsExactly("vector(768)", "vector(768)");
		assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE indexdef LIKE '%USING hnsw%'", String.class))
			.containsExactlyInAnyOrder("complaints_embedding_idx", "knowledge_chunks_embedding_idx");
	}

	@Test
	void everyFmcCategoryIsRoutedToADepartment() {
		Map<String, String> routing = jdbc
			.queryForList("SELECT c.name AS category, d.name AS department FROM categories c JOIN departments d ON d.id = c.department_id")
			.stream()
			.collect(Collectors.toMap(row -> (String) row.get("category"), row -> (String) row.get("department")));

		assertThat(routing).containsOnlyKeys("Roads", "Garbage", "Streetlights", "Drainage", "Water Supply", "Other");
		assertThat(routing).containsEntry("Streetlights", "Street Lighting & Electrical");
	}

	@Test
	void complaintCannotLeaveSubmittedWithoutAnOfficer() {
		long citizen = insertCitizen("citizen1@example.com");

		assertThatThrownBy(() -> insertComplaint(citizen, "ASSIGNED", UUID.randomUUID()))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void retriedSubmissionWithSameRequestIdIsRejected() {
		long citizen = insertCitizen("citizen2@example.com");
		UUID requestId = UUID.randomUUID();
		insertComplaint(citizen, "SUBMITTED", requestId);

		assertThatThrownBy(() -> insertComplaint(citizen, "SUBMITTED", requestId))
			.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void emailsMustBeStoredLowercase() {
		assertThatThrownBy(() -> insertCitizen("Mixed.Case@Example.com"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private long insertCitizen(String email) {
		return jdbc.queryForObject(
				"INSERT INTO users (name, email, password_hash, role) VALUES ('Test Citizen', ?, 'x', 'CITIZEN') RETURNING id",
				Long.class, email);
	}

	private void insertComplaint(long citizenId, String status, UUID requestId) {
		jdbc.update("""
				INSERT INTO complaints (citizen_id, request_id, title, description, location, category_id, status)
				VALUES (?, ?, 'Pothole', 'Deep pothole near the bus stop', 'MG Road', (SELECT min(id) FROM categories), ?)
				""", citizenId, requestId, status);
	}

}
