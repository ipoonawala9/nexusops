package com.nexusops.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesRequestIdWhenAbsentAndEchoesIt() throws Exception {
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), response, (req, res) -> {});
        assertThat(response.getHeader(RequestIdFilter.HEADER))
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void preservesValidIncomingRequestId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "abc-123_X.y");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc-123_X.y");
    }

    @Test
    void replacesHostileOrOversizedRequestIds() {
        assertThat(RequestIdFilter.sanitizeOrGenerate("abc\nFAKE LOG LINE")).doesNotContain("FAKE");
        assertThat(RequestIdFilter.sanitizeOrGenerate("a".repeat(65))).hasSize(36);
        assertThat(RequestIdFilter.sanitizeOrGenerate("")).hasSize(36);
        assertThat(RequestIdFilter.sanitizeOrGenerate(null)).hasSize(36);
    }

    @Test
    void populatesMdcDuringChainAndClearsAfter() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, "req-1");
        var seenRequestId = new AtomicReference<String>();
        var seenCorrelationId = new AtomicReference<String>();
        FilterChain chain = (req, res) -> {
            seenRequestId.set(MDC.get(RequestIdFilter.MDC_REQUEST_ID));
            seenCorrelationId.set(MDC.get(RequestIdFilter.MDC_CORRELATION_ID));
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenRequestId.get()).isEqualTo("req-1");
        assertThat(seenCorrelationId.get()).isEqualTo("req-1"); // defaults to the request id
        assertThat(MDC.get(RequestIdFilter.MDC_REQUEST_ID)).isNull();
        assertThat(MDC.get(RequestIdFilter.MDC_CORRELATION_ID)).isNull();
    }

    @Test
    void usesIncomingCorrelationIdWhenValid() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.CORRELATION_HEADER, "corr-9");
        var seen = new AtomicReference<String>();
        filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_CORRELATION_ID)));
        assertThat(seen.get()).isEqualTo("corr-9");
    }
}
