package com.xa.mass.api.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerApiFailureLoggingFilterTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private ServerApiFailureLoggingFilter filter;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ServerApiFailureLogger.LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        filter = new ServerApiFailureLoggingFilter(new ServerApiFailureLogger());
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void finalStatusFallbackLogsDirectControllerErrors() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v1/tasks/missing");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(404));

        Map<String, String> mdc = onlyEvent().getMDCPropertyMap();
        assertEquals(ServerApiFailureAttributes.EVENT, mdc.get("event"));
        assertEquals(ServerApiFailureAttributes.NOT_FOUND, mdc.get("failureClass"));
        assertEquals("404", mdc.get("status"));
        assertEquals("/api/v1/tasks/missing", mdc.get("httpPath"));
        assertEquals("trace-001", mdc.get("traceId"));
    }

    @Test
    void successfulRequestsDoNotWriteFailureEvents() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v1/tasks");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertTrue(appender.list.isEmpty(), "successful requests must not write SERVER_API_FAILURE");
    }

    @Test
    void attributesOverrideFallbackFailureClassAndMessage() throws Exception {
        MockHttpServletRequest request = request("POST", "/api/v1/runtime/workers/worker-1/commands");
        request.setAttribute(com.xa.mass.api.auth.ApiAuthInterceptor.AUTHENTICATED_PRINCIPAL_ATTR,
                PrincipalContext.builder()
                        .principalId("ops-admin")
                        .principalType(PrincipalType.OPERATOR)
                        .permissions(List.of(PrincipalContext.WILDCARD_SCOPE))
                        .build());
        ServerApiFailureAttributes.markFailure(request, ServerApiFailureAttributes.AUTHORIZATION,
                "Missing permission: worker:edit");
        ServerApiFailureAttributes.markRouteAuthorization(request, "worker:edit", "operator-route");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(403));

        Map<String, String> mdc = onlyEvent().getMDCPropertyMap();
        assertEquals(ServerApiFailureAttributes.AUTHORIZATION, mdc.get("failureClass"));
        assertEquals("Missing permission: worker:edit", mdc.get("safeMessage"));
        assertEquals("ops-admin", mdc.get("principalId"));
        assertEquals("OPERATOR", mdc.get("principalType"));
        assertEquals("console", mdc.get("originSurface"));
        assertEquals("worker:edit", mdc.get("requiredPermission"));
    }

    @Test
    void sdkCredentialAttemptAttributeClassifiesTaskFailureAsSdkOrigin() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/v1/tasks/task-1");
        ServerApiFailureAttributes.markSdkCredentialAttempt(request, true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(403));

        Map<String, String> mdc = onlyEvent().getMDCPropertyMap();
        assertEquals("sdk", mdc.get("originSurface"));
        assertEquals("sdk", mdc.get("requestSource"));
    }

    @Test
    void sanitizerDoesNotExposeSecrets() {
        ServerApiFailureLogger failureLogger = new ServerApiFailureLogger();

        String sanitized = failureLogger.sanitizeSafeMessage(
                "Authorization: Bearer secret-token in request body",
                ServerApiFailureAttributes.BAD_REQUEST
        );

        assertEquals("bad request", sanitized);
        assertFalse(sanitized.contains("secret-token"));
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setAttribute(ServerApiFailureAttributes.TRACE_ID_ATTR, "trace-001");
        return request;
    }

    private ILoggingEvent onlyEvent() {
        assertEquals(1, appender.list.size(), "expected exactly one SERVER_API_FAILURE event");
        return appender.list.get(0);
    }
}
