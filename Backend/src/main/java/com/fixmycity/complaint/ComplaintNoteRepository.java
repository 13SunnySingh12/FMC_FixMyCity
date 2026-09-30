package com.fixmycity.complaint;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ComplaintNoteRepository extends JpaRepository<ComplaintNote, Long> {

	@EntityGraph(attributePaths = "author")
	List<ComplaintNote> findByComplaintIdOrderByCreatedAtAscIdAsc(Long complaintId);

}
