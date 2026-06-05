package com.xa.mass.server.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.xa.mass.api.aop.GlobalExceptionHandler;
import com.xa.mass.api.filter.RequestMdcCleanupFilter;
import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.api.observability.ServerApiFailureAttributes;
import com.xa.mass.api.observability.ServerApiFailureLogger;
import com.xa.mass.api.observability.ServerApiFailureLoggingFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ServerApiFailureLoggingIntegrationTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(ServerApiFailureLogger.LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ServerApiFailureLogger failureLogger = new ServerApiFailureLogger();
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new RequestMdcCleanupFilter(), new ServerApiFailureLoggingFilter(failureLogger))
                .build();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void representativeFailuresWriteServerApiFailureEvents() throws Exception {
        mockMvc.perform(get("/api/v1/probe/auth-failure"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/probe/direct-bad-request"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/probe/direct-bad-request"))
                .andExpect(status().isMethodNotAllowed());

        assertEquals(3, appender.list.size());
        for (ILoggingEvent event : appender.list) {
            Map<String, String> mdc = event.getMDCPropertyMap();
            assertEquals(ServerApiFailureAttributes.EVENT, mdc.get("event"));
            assertNotNull(mdc.get("traceId"));
            assertNotNull(mdc.get("httpMethod"));
            assertNotNull(mdc.get("httpPath"));
            assertNotNull(mdc.get("status"));
            assertNotNull(mdc.get("failureClass"));
            assertFalse(mdc.toString().contains("Authorization"));
            assertFalse(mdc.toString().contains("secret-token"));
        }
    }

    @Test
    void successfulRequestsDoNotWriteServerApiFailureEvents() throws Exception {
        mockMvc.perform(get("/api/v1/probe/success"))
                .andExpect(status().isOk());

        assertEquals(0, appender.list.size());
    }

    @RestController
    @RequestMapping("/api/v1/probe")
    static class TestController {

        @GetMapping("/success")
        Map<String, Object> success() {
            return Map.of("ok", true);
        }

        @GetMapping("/auth-failure")
        ResponseEntity<ApiResponse<Object>> authFailure() {
            return ResponseEntity.status(401).body(ApiResponse.error(401, "Invalid or missing operator session"));
        }

        @GetMapping("/direct-bad-request")
        ResponseEntity<ApiResponse<Object>> directBadRequest() {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "bad request"));
        }

    }
}
