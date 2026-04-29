package com.xa.mass.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiAuthInterceptorTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(new ApiAuthService(), new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new ProtectedApiController())
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void anonymousUserCannotReadProtectedTaskList() throws Exception {
        mockMvc.perform(get("/status/api/tasks")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void viewerCanReadLegacyQueueDiagnostics() throws Exception {
        mockMvc.perform(get("/api/queue/status")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void anonymousUserCannotReachLegacyQueueDiagnostics() throws Exception {
        mockMvc.perform(get("/api/queue/status")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void anonymousUserCannotReachLegacySessionDiagnostics() throws Exception {
        mockMvc.perform(get("/api/session/list")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedUserCanLoadMe() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void authenticatedUserCanLoadProjectOptions() throws Exception {
        mockMvc.perform(get("/api/config/projects")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void sdkCredentialAttemptCanReachUnifiedTaskCreateWithoutOperatorPermission() throws Exception {
        mockMvc.perform(post("/status/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "taskName":"sdk-task",
                                  "eventCode":"crawler.fetch-page",
                                  "inputs":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Controller
    static class ProtectedApiController {
        @GetMapping("/status/api/tasks")
        @ResponseBody
        public Map<String, Object> tasks() {
            return Map.of("ok", true);
        }

        @PostMapping("/status/api/tasks")
        @ResponseBody
        public Map<String, Object> createTask(@RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "body", body);
        }

        @GetMapping("/api/queue/status")
        @ResponseBody
        public Map<String, Object> queueStatus() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/session/list")
        @ResponseBody
        public Map<String, Object> sessionList() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/auth/me")
        @ResponseBody
        public Map<String, Object> me() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/config/projects")
        @ResponseBody
        public Map<String, Object> projects() {
            return Map.of("ok", true);
        }
    }
}
