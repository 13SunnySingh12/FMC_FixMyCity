package com.fixmycity.complaint;

/** Published inside the submitting transaction; listeners act after it commits. */
public record ComplaintSubmitted(long complaintId) {

}
