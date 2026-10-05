package com.nexusops.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Creates real workspaces through the public API. */
public final class TestTenants {

    public static final String PASSWORD = "correct horse battery staple";

    public record Workspace(UUID tenantId, String slug, String email, String password) {}

    private TestTenants() {}

    public static String uniqueSlug(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static Workspace signup(MockMvc mvc, String slug) throws Exception {
        String email = "owner@" + slug + ".test";
        mvc.perform(post("/api/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspaceName":"%s Inc","slug":"%s","firstName":"Ada","lastName":"Owner","email":"%s","password":"%s"}
                """.formatted(slug, slug, email, PASSWORD)))
                .andExpect(status().isCreated());
        UUID tenantId = OwnerJdbc.jdbc().queryForObject("select id from tenants where slug = ?", UUID.class, slug);
        return new Workspace(tenantId, slug, email, PASSWORD);
    }

    public static Workspace signupAndVerify(MockMvc mvc, RecordingMailSender mail, String slug) throws Exception {
        Workspace workspace = signup(mvc, slug);
        mvc.perform(post("/api/v1/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + mail.lastTokenFor(workspace.email()) + "\"}"))
                .andExpect(status().isNoContent());
        return workspace;
    }

    public record Session(String accessToken, String refreshToken) {}

    private static final java.util.regex.Pattern ACCESS = java.util.regex.Pattern.compile("\"accessToken\":\"([^\"]+)\"");
    private static final java.util.regex.Pattern COOKIE = java.util.regex.Pattern.compile("nexus_rt=([^;]*)");

    public static Session login(MockMvc mvc, Workspace workspace) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                {"workspace":"%s","email":"%s","password":"%s"}""".formatted(workspace.slug(), workspace.email(), workspace.password())))
                .andExpect(status().isOk())
                .andReturn();
        return new Session(accessTokenOf(result), refreshCookieOf(result));
    }

    public static String accessTokenOf(org.springframework.test.web.servlet.MvcResult result) throws Exception {
        var matcher = ACCESS.matcher(result.getResponse().getContentAsString());
        if (!matcher.find()) throw new AssertionError("no accessToken in response");
        return matcher.group(1);
    }

    public static String refreshCookieOf(org.springframework.test.web.servlet.MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            var matcher = COOKIE.matcher(header);
            if (matcher.find()) return matcher.group(1);
        }
        throw new AssertionError("no nexus_rt cookie set");
    }
}
