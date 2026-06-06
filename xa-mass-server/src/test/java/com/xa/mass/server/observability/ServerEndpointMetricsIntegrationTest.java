package com.xa.mass.server.observability;

import com.xa.mass.api.model.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {
                ServerEndpointMetricsIntegrationTest.TestApplication.class,
                ServerEndpointMetricsIntegrationTest.ProbeController.class
        },
        properties = {
                "management.endpoints.web.exposure.include=health,metrics",
                "spring.main.banner-mode=off"
        }
)
@AutoConfigureMockMvc
class ServerEndpointMetricsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void actuatorHttpServerRequestsRecordsSuccessfulAndFailedApiRequests() throws Exception {
        mockMvc.perform(get("/api/v1/probe/success"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/probe/direct-bad-request"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/actuator/metrics/http.server.requests"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http.server.requests")))
                .andExpect(content().string(containsString("\"tag\":\"uri\"")))
                .andExpect(content().string(containsString("/api/v1/probe/success")))
                .andExpect(content().string(containsString("/api/v1/probe/direct-bad-request")));
    }

    @SpringBootApplication
    static class TestApplication {
    }

    @RestController
    @RequestMapping("/api/v1/probe")
    static class ProbeController {

        @GetMapping("/success")
        ResponseEntity<ApiResponse<Object>> success() {
            return ResponseEntity.ok(ApiResponse.success("ok"));
        }

        @GetMapping("/direct-bad-request")
        ResponseEntity<ApiResponse<Object>> directBadRequest() {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "bad request"));
        }
    }
}
