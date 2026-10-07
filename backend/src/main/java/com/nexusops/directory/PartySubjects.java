package com.nexusops.directory;

import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.domain.Party;
import com.nexusops.directory.domain.PartyRepository;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** People and organizations as collaboration subjects (type PARTY). */
@Component
class PartySubjects implements SubjectResolver {

    static final String TYPE = "PARTY";

    private final PartyRepository parties;

    PartySubjects(PartyRepository parties) {
        this.parties = parties;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return DirectoryPermissions.PARTY_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return parties.findById(id).map(PartySubjects::ref);
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return parties.findAllById(ids).stream().collect(Collectors.toMap(Party::getId, PartySubjects::ref));
    }

    private static SubjectRef ref(Party party) {
        return new SubjectRef(TYPE, party.getId(), party.getName(), party.isArchived());
    }
}
