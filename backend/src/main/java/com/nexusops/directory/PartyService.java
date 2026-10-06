package com.nexusops.directory;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.directory.domain.ContactDetails;
import com.nexusops.directory.domain.OrganizationDetails;
import com.nexusops.directory.domain.Party;
import com.nexusops.directory.domain.PartyNames;
import com.nexusops.directory.domain.PartyRepository;
import com.nexusops.directory.domain.PartyRoleRepository;
import com.nexusops.directory.domain.PersonDetails;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * People and organizations (ADR-0008). Creating, or editing into, a probable duplicate is refused with the candidates
 * unless the caller gives a reason; checks are serialized per tenant so concurrent creates can't both slip through.
 */
@Service
public class PartyService {

    static final String NOT_FOUND = "Record not found.";
    static final String ARCHIVED = "This record is archived.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String DUPLICATE =
            "This looks like a record that already exists. Open it, or give a reason for keeping a separate one.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";
    private static final String IDENTITY_LOCK = "party-identity";

    private final PartyRepository parties;
    private final PartyRoleRepository roles;
    private final TenantLocks locks;
    private final AuditService audit;

    PartyService(PartyRepository parties, PartyRoleRepository roles, TenantLocks locks, AuditService audit) {
        this.parties = parties;
        this.roles = roles;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PartyView get(UUID id) {
        return view(find(id));
    }

    @Transactional
    public PartyView createPerson(PersonCommand command) {
        TenantContext.requireTenantId();
        PersonDetails details = person(command, null);
        String reason = duplicateReason(command.duplicateReason());
        locks.lock(IDENTITY_LOCK);
        List<Party> duplicates = personDuplicates(details, null);
        requireUnique(duplicates, reason);
        return created(Party.person(Ids.newId(), details), "PersonCreated", duplicates, reason);
    }

    @Transactional
    public PartyView createOrganization(OrganizationCommand command) {
        TenantContext.requireTenantId();
        OrganizationDetails details = organization(command);
        String reason = duplicateReason(command.duplicateReason());
        locks.lock(IDENTITY_LOCK);
        List<Party> duplicates = organizationDuplicates(details, null);
        requireUnique(duplicates, reason);
        return created(Party.organization(Ids.newId(), details), "OrganizationCreated", duplicates, reason);
    }

    @Transactional
    public PartyView updatePerson(UUID id, PersonCommand command, Long version) {
        Party party = editable(id, PartyKind.PERSON, version);
        PersonDetails details = person(command, party.getOrganizationId());
        String reason = duplicateReason(command.duplicateReason());
        boolean identityChanged = !Objects.equals(details.email(), party.getEmail())
                || !PartyNames.personKey(details.firstName(), details.lastName()).equals(party.getNameKey())
                || !Objects.equals(details.organizationId(), party.getOrganizationId());
        List<Party> duplicates = List.of();
        if (identityChanged) {
            locks.lock(IDENTITY_LOCK);
            duplicates = personDuplicates(details, id);
            requireUnique(duplicates, reason);
        }
        Map<String, Object> before = snapshot(party);
        party.applyPerson(details);
        return updated(party, before, duplicates, reason);
    }

    @Transactional
    public PartyView updateOrganization(UUID id, OrganizationCommand command, Long version) {
        Party party = editable(id, PartyKind.ORGANIZATION, version);
        OrganizationDetails details = organization(command);
        String reason = duplicateReason(command.duplicateReason());
        boolean identityChanged = !Objects.equals(details.domain(), party.getDomain())
                || !PartyNames.organizationKey(details.name()).equals(party.getNameKey());
        List<Party> duplicates = List.of();
        if (identityChanged) {
            locks.lock(IDENTITY_LOCK);
            duplicates = organizationDuplicates(details, id);
            requireUnique(duplicates, reason);
        }
        Map<String, Object> before = snapshot(party);
        party.applyOrganization(details);
        return updated(party, before, duplicates, reason);
    }

    Party find(UUID id) {
        TenantContext.requireTenantId();
        return parties.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
    }

    PartyView view(Party party) {
        PartyRef organization = party.getOrganizationId() == null ? null : parties.findById(party.getOrganizationId())
                .map(o -> new PartyRef(o.getId(), o.getName())).orElse(null);
        boolean employees = CurrentAuthorities.has(DirectoryPermissions.EMPLOYEE_READ);
        List<PartyRoleView> roleViews = roles.findByPartyIdOrderByRoleAsc(party.getId()).stream()
                .filter(r -> employees || r.getRole() != PartyRoleType.EMPLOYEE)
                .map(r -> new PartyRoleView(r.getRole(), r.getStatus(), r.getSince(), r.getEmployeeNumber()))
                .toList();
        return new PartyView(party.getId(), party.getKind(), party.getName(), party.getFirstName(), party.getLastName(),
                party.getJobTitle(), organization, party.getEmail(), party.getPhone(), party.getDomain(),
                party.getWebsite(), roleViews, party.getDuplicateReason(), party.getArchivedAt(), party.getCreatedAt(),
                party.getUpdatedAt(), party.getVersion());
    }

    private Party editable(UUID id, PartyKind kind, Long version) {
        Party party = find(id);
        if (party.getKind() != kind) {
            throw ApiProblem.notFound(NOT_FOUND);
        }
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (party.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
        if (party.isArchived()) {
            throw ApiProblem.conflict(ARCHIVED);
        }
        return party;
    }

    private PersonDetails person(PersonCommand command, UUID currentOrganizationId) {
        String firstName = Text.required(command.firstName(), 80, "firstName");
        String lastName = Text.optional(command.lastName(), 80, "lastName");
        String jobTitle = Text.optional(command.jobTitle(), 100, "jobTitle");
        String email = ContactDetails.email(command.email());
        String phone = ContactDetails.phone(command.phone());
        UUID organizationId = command.organizationId();
        if (organizationId != null && !organizationId.equals(currentOrganizationId)) {
            Party target = parties.findById(organizationId).filter(p -> p.getKind() == PartyKind.ORGANIZATION)
                    .orElseThrow(() -> ApiProblem.badRequestField("organizationId",
                            "Choose an organization in this workspace."));
            if (target.isArchived()) {
                throw ApiProblem.badRequestField("organizationId", "This organization is archived.");
            }
        }
        return new PersonDetails(firstName, lastName, jobTitle, organizationId, email, phone);
    }

    private static OrganizationDetails organization(OrganizationCommand command) {
        return new OrganizationDetails(Text.required(command.name(), 200, "name"),
                ContactDetails.domain(command.domain()), ContactDetails.website(command.website()),
                ContactDetails.email(command.email()), ContactDetails.phone(command.phone()));
    }

    private static String duplicateReason(String raw) {
        return Text.optional(raw, 500, "duplicateReason");
    }

    /** Same email, or the same name in the same organization (both without one counts as the same). */
    private List<Party> personDuplicates(PersonDetails details, UUID self) {
        Map<UUID, Party> found = new LinkedHashMap<>();
        if (details.email() != null) {
            parties.findByKindAndEmail(PartyKind.PERSON, details.email()).forEach(p -> found.put(p.getId(), p));
        }
        parties.findByKindAndNameKey(PartyKind.PERSON, PartyNames.personKey(details.firstName(), details.lastName()))
                .stream().filter(p -> Objects.equals(p.getOrganizationId(), details.organizationId()))
                .forEach(p -> found.put(p.getId(), p));
        if (self != null) {
            found.remove(self);
        }
        return List.copyOf(found.values());
    }

    /** Same domain, or the same name ignoring case, punctuation and legal suffixes. */
    private List<Party> organizationDuplicates(OrganizationDetails details, UUID self) {
        Map<UUID, Party> found = new LinkedHashMap<>();
        if (details.domain() != null) {
            parties.findByKindAndDomain(PartyKind.ORGANIZATION, details.domain()).forEach(p -> found.put(p.getId(), p));
        }
        parties.findByKindAndNameKey(PartyKind.ORGANIZATION, PartyNames.organizationKey(details.name()))
                .forEach(p -> found.put(p.getId(), p));
        if (self != null) {
            found.remove(self);
        }
        return List.copyOf(found.values());
    }

    private static void requireUnique(List<Party> duplicates, String reason) {
        if (!duplicates.isEmpty() && reason == null) {
            throw ApiProblem.conflict(DUPLICATE).withProperty("duplicates", duplicates.stream()
                    .map(p -> new DuplicateCandidate(p.getId(), p.getKind(), p.getName(), p.getEmail(), p.getDomain(),
                            p.isArchived()))
                    .toList());
        }
    }

    private PartyView created(Party party, String action, List<Party> duplicates, String reason) {
        if (!duplicates.isEmpty()) {
            party.recordDuplicateReason(reason);
        }
        parties.saveAndFlush(party);
        audit.record(AuditEntry.of(action, "Party", party.getId()).withAfter(snapshot(party))
                .withMetadata(duplicateMetadata(duplicates, reason)));
        return view(party);
    }

    private PartyView updated(Party party, Map<String, Object> before, List<Party> duplicates, String reason) {
        if (!duplicates.isEmpty()) {
            party.recordDuplicateReason(reason);
        }
        parties.flush();
        audit.record(AuditEntry.of("PartyUpdated", "Party", party.getId()).withBefore(before)
                .withAfter(snapshot(party)).withMetadata(duplicateMetadata(duplicates, reason)));
        return view(party);
    }

    private static Map<String, Object> duplicateMetadata(List<Party> duplicates, String reason) {
        if (duplicates.isEmpty()) {
            return null;
        }
        return Map.of("duplicateOf", duplicates.stream().map(p -> p.getId().toString()).toList(), "reason", reason);
    }

    static Map<String, Object> snapshot(Party party) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("kind", party.getKind().name());
        values.put("name", party.getName());
        putIfPresent(values, "jobTitle", party.getJobTitle());
        putIfPresent(values, "organizationId", party.getOrganizationId());
        putIfPresent(values, "email", party.getEmail());
        putIfPresent(values, "phone", party.getPhone());
        putIfPresent(values, "domain", party.getDomain());
        putIfPresent(values, "website", party.getWebsite());
        return values;
    }

    private static void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null) {
            values.put(key, value.toString());
        }
    }
}
