package com.fixmycity.complaint;

import java.util.EnumSet;
import java.util.Set;

/** FMC's complaint timeline: Submitted → Assigned → In Progress → Resolved → Closed. */
public enum ComplaintStatus {

	SUBMITTED, ASSIGNED, IN_PROGRESS, RESOLVED, CLOSED;

	/** Statuses in which an officer is actively responsible for the work. */
	public static final Set<ComplaintStatus> ACTIVE_WORK = EnumSet.of(ASSIGNED, IN_PROGRESS);

}
