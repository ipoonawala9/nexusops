package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.CategoryCommand;
import com.nexusops.helpdesk.Channel;
import com.nexusops.helpdesk.MessageCommand;
import com.nexusops.helpdesk.MessageKind;
import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.SlaPolicyCommand;
import com.nexusops.helpdesk.TicketCommand;
import com.nexusops.helpdesk.TicketStatus;
import java.util.UUID;

final class HelpDeskDtos {

    private HelpDeskDtos() {}

    record CategoryRequest(String name, String description, UUID defaultAssigneeId, Long version) {
        CategoryCommand command() {
            return new CategoryCommand(name, description, defaultAssigneeId);
        }
    }

    record SlaPolicyRequest(Integer firstResponseMinutes, Integer resolutionMinutes, Long version) {
        SlaPolicyCommand command() {
            return new SlaPolicyCommand(firstResponseMinutes, resolutionMinutes);
        }
    }

    record VersionRequest(Long version) {}

    record TicketRequest(String subject, String description, UUID requesterId, UUID productId, String linkedType,
            UUID linkedId, UUID categoryId, Priority priority, Channel channel, UUID assigneeId, Long version) {
        TicketCommand command() {
            return new TicketCommand(subject, description, requesterId, productId, linkedType, linkedId, categoryId,
                    priority, channel, assigneeId);
        }
    }

    record AssignRequest(UUID assigneeId, Long version) {}

    record StatusRequest(TicketStatus status, String note, Long version) {}

    record MessageRequest(MessageKind kind, String body) {
        MessageCommand command() {
            return new MessageCommand(kind, body);
        }
    }
}
