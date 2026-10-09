package com.nexusops.helpdesk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestTenants;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Disabling HelpDesk removes every HelpDesk permission, so every HelpDesk route answers 403 (criterion 5). */
@AutoConfigureMockMvc
class HelpDeskModuleGateIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyHelpDeskHandlerRequiresAHelpDeskPermission() {
        List<String> violations = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                if (!path.startsWith("/api/v1/helpdesk/")) {
                    continue;
                }
                var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
                String expression = preAuthorize == null ? "" : preAuthorize.value().replace(" ", "");
                checked.add(path);
                if (!expression.startsWith("hasAuthority('helpdesk.")) {
                    violations.add(info.getMethodsCondition() + " " + path + " -> " + expression);
                }
            }
        });
        assertThat(checked).as("HelpDesk routes were found").hasSizeGreaterThanOrEqualTo(15);
        assertThat(violations).isEmpty();
    }

    @Test
    void everyHelpDeskRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hdgate")));
        TestHelpDesk.enable(owner);
        UUID category = TestHelpDesk.category(owner, "General");
        owner.put("/api/v1/tenant/modules/HELPDESK", "{\"enabled\":false}").andExpect(status().isOk());
        UUID any = UUID.randomUUID();
        owner.get("/api/v1/helpdesk/tickets").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/tickets/" + any).andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets/" + any + "/messages", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets/" + any + "/status", "{}").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/tickets/" + any + "/assign", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/tickets/" + any + "/context").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/categories").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/categories/" + category + "/archive", "").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/sla-policies").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/articles").andExpect(status().isForbidden());
        owner.post("/api/v1/helpdesk/articles", "{}").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/dashboard").andExpect(status().isForbidden());
        owner.get("/api/v1/helpdesk/agents").andExpect(status().isForbidden());
        TestHelpDesk.enable(owner);
        owner.get("/api/v1/helpdesk/tickets").andExpect(status().isOk());
    }
}
