package com.nexusops.crm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestCrm;
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

/** Disabling CRM removes every CRM permission, so every CRM route answers 403 (spec success criterion 4). */
@AutoConfigureMockMvc
class CrmModuleGateIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void everyCrmHandlerRequiresACrmPermission() {
        List<String> violations = new ArrayList<>();
        Set<String> checked = new HashSet<>();
        mapping.getHandlerMethods().forEach((info, handler) -> {
            for (String path : info.getPatternValues()) {
                if (!(path.startsWith("/api/v1/leads") || path.startsWith("/api/v1/opportunities")
                        || path.startsWith("/api/v1/crm/"))) {
                    continue;
                }
                var preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
                if (preAuthorize == null) {
                    preAuthorize = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
                }
                String expression = preAuthorize == null ? "" : preAuthorize.value().replace(" ", "");
                checked.add(path);
                if (!(expression.contains("hasAuthority('crm.") || expression.contains("hasAnyAuthority('crm."))) {
                    violations.add(info.getMethodsCondition() + " " + path + " -> " + expression);
                }
            }
        });
        assertThat(checked).as("CRM routes were found").hasSizeGreaterThanOrEqualTo(10);
        assertThat(violations).isEmpty();
    }

    @Test
    void everyCrmRouteIsForbiddenWhileTheModuleIsOff() throws Exception {
        Api owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("gate")));
        TestCrm.enable(owner);
        UUID lead = Api.id(owner.post("/api/v1/leads", "{\"lastName\":\"Gate\"}"));
        owner.put("/api/v1/tenant/modules/CRM", "{\"enabled\":false}").andExpect(status().isOk());
        owner.get("/api/v1/leads").andExpect(status().isForbidden());
        owner.get("/api/v1/leads/" + lead).andExpect(status().isForbidden());
        owner.post("/api/v1/leads", "{\"lastName\":\"X\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/opportunities").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/pipeline/stages").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/pipeline/board").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/customers").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/dashboard").andExpect(status().isForbidden());
        owner.get("/api/v1/crm/owners").andExpect(status().isForbidden());
        owner.get("/api/v1/activities?subjectType=LEAD&subjectId=" + lead).andExpect(status().isForbidden());
        owner.post("/api/v1/opportunities", "{\"name\":\"X\",\"accountId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isForbidden());
        owner.post("/api/v1/opportunities/" + UUID.randomUUID() + "/stage",
                "{\"stageId\":\"" + UUID.randomUUID() + "\",\"version\":0}").andExpect(status().isForbidden());
        owner.put("/api/v1/leads/" + lead, "{\"lastName\":\"X\",\"version\":0}").andExpect(status().isForbidden());
        owner.post("/api/v1/leads/" + lead + "/convert", "{\"organization\":{\"name\":\"X\"},\"version\":0}")
                .andExpect(status().isForbidden());
        owner.put("/api/v1/crm/pipeline/stages/order", "{\"stageIds\":[]}").andExpect(status().isForbidden());
        TestCrm.enable(owner);
        owner.get("/api/v1/leads/" + lead).andExpect(status().isOk());
    }
}
