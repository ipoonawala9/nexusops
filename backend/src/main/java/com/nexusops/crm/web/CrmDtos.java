package com.nexusops.crm.web;

import com.nexusops.crm.StageCommand;
import java.util.List;
import java.util.UUID;

final class CrmDtos {

    private CrmDtos() {}

    record StageRequest(String name, Integer probability, Long version) {
        StageCommand command() {
            return new StageCommand(name, probability);
        }
    }

    record StageOrderRequest(List<UUID> stageIds) {}
}
