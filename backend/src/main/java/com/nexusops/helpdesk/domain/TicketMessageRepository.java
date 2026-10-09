package com.nexusops.helpdesk.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketMessageRepository extends JpaRepository<TicketMessage, UUID> {

    List<TicketMessage> findByTicketIdOrderByCreatedAtAscIdAsc(UUID ticketId);
}
