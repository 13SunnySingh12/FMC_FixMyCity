package com.fixmycity.admin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** FMC's basic analytics for the admin dashboard (admin-only via the /api/admin path). */
@RestController
class AnalyticsController {

	record Count(String name, long count) {
	}

	record Analytics(long total, long pending, long resolved, Map<String, Long> byStatus,
			Map<String, Long> byPriority, List<Count> byCategory, List<Count> byDepartment, long aiFailed) {
	}

	private final JdbcTemplate jdbc;

	AnalyticsController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/api/admin/analytics")
	@Transactional(readOnly = true)
	Analytics analytics() {
		Map<String, Long> byStatus = counts("""
				SELECT s.status, count(c.id) FROM (VALUES (1, 'SUBMITTED'), (2, 'ASSIGNED'), (3, 'IN_PROGRESS'),
				    (4, 'RESOLVED'), (5, 'CLOSED')) AS s(position, status)
				LEFT JOIN complaints c ON c.status = s.status GROUP BY s.position, s.status ORDER BY s.position""");
		// Priority is empty until the AI suggests one or an admin sets it; show that honestly as UNSET.
		Map<String, Long> byPriority = counts("""
				SELECT p.priority, count(c.id) FROM (VALUES (1, 'HIGH'), (2, 'MEDIUM'), (3, 'LOW'), (4, 'UNSET'))
				    AS p(position, priority)
				LEFT JOIN complaints c ON coalesce(c.priority, 'UNSET') = p.priority
				GROUP BY p.position, p.priority ORDER BY p.position""");
		List<Count> byCategory = this.jdbc.query("""
				SELECT cat.name, count(c.id) FROM categories cat LEFT JOIN complaints c ON c.category_id = cat.id
				GROUP BY cat.id, cat.name ORDER BY count(c.id) DESC, cat.id""",
				(rs, row) -> new Count(rs.getString(1), rs.getLong(2)));
		List<Count> byDepartment = this.jdbc.query("""
				SELECT d.name, count(c.id) FROM departments d LEFT JOIN complaints c ON c.department_id = d.id
				GROUP BY d.id, d.name ORDER BY count(c.id) DESC, d.id""",
				(rs, row) -> new Count(rs.getString(1), rs.getLong(2)));
		long aiFailed = this.jdbc.queryForObject("SELECT count(*) FROM complaints WHERE ai_status = 'FAILED'", Long.class);
		long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
		long pending = byStatus.get("SUBMITTED") + byStatus.get("ASSIGNED") + byStatus.get("IN_PROGRESS");
		long resolved = byStatus.get("RESOLVED") + byStatus.get("CLOSED");
		return new Analytics(total, pending, resolved, byStatus, byPriority, byCategory, byDepartment, aiFailed);
	}

	private Map<String, Long> counts(String sql) {
		Map<String, Long> counts = new LinkedHashMap<>();
		this.jdbc.query(sql, (rs) -> {
			counts.put(rs.getString(1), rs.getLong(2));
		});
		return counts;
	}

}
