package com.nexusops.collaboration;

import java.util.List;
import java.util.UUID;

/**
 * Implemented by modules that know which other records belong to a subject (e.g. CRM: a customer's opportunities).
 * Used to merge timelines (spec D10). Tenant-scoped; return at most a few hundred keys.
 */
public interface SubjectRelations {

    List<SubjectKey> related(String type, UUID id);
}
