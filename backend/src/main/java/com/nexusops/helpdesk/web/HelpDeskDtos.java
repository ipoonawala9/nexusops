package com.nexusops.helpdesk.web;

import com.nexusops.helpdesk.CategoryCommand;
import com.nexusops.helpdesk.SlaPolicyCommand;
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
}
