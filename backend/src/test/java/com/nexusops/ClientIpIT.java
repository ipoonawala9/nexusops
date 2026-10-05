package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** Runs against the real embedded Tomcat (not MockMvc) so the RemoteIpValve is exercised. */
class ClientIpIT extends IntegrationTestSupport {

    @Value("${local.server.port}")
    int port;

    @Test
    void clientIpComesFromXForwardedForSentByAnInternalProxy() throws Exception {
        String workspace = "ip-probe-" + System.nanoTime();
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "203.0.113.9")
                .POST(HttpRequest.BodyPublishers.ofString("""
                        {"workspace":"%s","email":"a@b.test","password":"whatever-123456"}""".formatted(workspace)))
                .build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401);

        String ip = OwnerJdbc.superuser().queryForObject(
                "select ip from audit_events where action = 'LoginFailed' and metadata->>'workspace' = ?",
                String.class, workspace);
        assertThat(ip).isEqualTo("203.0.113.9");
    }
}
