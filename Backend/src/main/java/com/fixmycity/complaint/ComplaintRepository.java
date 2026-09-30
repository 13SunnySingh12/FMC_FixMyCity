package com.fixmycity.complaint;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ComplaintRepository extends JpaRepository<Complaint, Long>, JpaSpecificationExecutor<Complaint> {

	@Override
	@EntityGraph(attributePaths = { "category", "department", "assignedOfficer", "citizen" })
	Page<Complaint> findAll(Specification<Complaint> spec, Pageable pageable);

	@EntityGraph(attributePaths = { "category", "department", "assignedOfficer", "citizen", "aiCategory",
			"aiDepartment" })
	Optional<Complaint> findWithDetailsById(Long id);

	Optional<Complaint> findByCitizenIdAndRequestId(Long citizenId, UUID requestId);

	boolean existsByAssignedOfficerIdAndStatusIn(Long officerId, Collection<ComplaintStatus> statuses);

}
