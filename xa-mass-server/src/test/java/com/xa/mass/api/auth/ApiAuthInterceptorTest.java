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
    void authenticatedUserWithoutWorkerViewCannotLoadProjectOptions() throws Exception {
        mockMvc.perform(get("/api/config/projects")
                        .header(ApiAuthService.USER_MODE_HEADER, "custom")
                        .header(ApiAuthService.USER_ID_HEADER, "limited-user")
                        .header(ApiAuthService.USER_NAME_HEADER, "Limited User")
                        .header(ApiAuthService.USER_PERMISSIONS_HEADER, ApiPermissionNames.TASK_VIEW))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void anonymousUserCannotReachUnmappedApiRoute() throws Exception {
        mockMvc.perform(post("/api/internal/legacy-probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void anonymousUserCannotReachSyncTaskCreateWithoutSdkCredential() throws Exception {
        mockMvc.perform(post("/status/api/tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .content("""
                                {
                                  "taskName":"sdk-sync-task",
                                  "eventCode":"crawler.fetch-page",
                                  "inputs":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void sdkCredentialAttemptCanReachUnifiedSyncTaskCreateWithoutOperatorPermission() throws Exception {
        mockMvc.perform(post("/status/api/tasks/sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "taskName":"sdk-sync-task",
                                  "eventCode":"crawler.fetch-page",
                                  "inputs":[{"url":"https://example.test"}]
                                }
                                """))
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

    @Test
    void sdkCredentialAttemptCanReachUnifiedTaskListWithoutOperatorPermission() throws Exception {
        mockMvc.perform(get("/status/api/tasks")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void sdkCredentialAttemptCanReachUnifiedTaskDetailWithoutOperatorPermission() throws Exception {
        mockMvc.perform(get("/status/api/tasks/task-001")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void sdkCredentialAttemptCanReachUnifiedTaskMessagesWithoutOperatorPermission() throws Exception {
        mockMvc.perform(get("/status/api/tasks/task-001/messages")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
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

        @GetMapping("/status/api/tasks/{taskId}")
        @ResponseBody
        public Map<String, Object> taskDetail(@PathVariable String taskId) {
            return Map.of("ok", true, "taskId", taskId);
        }

        @GetMapping("/status/api/tasks/{taskId}/messages")
        @ResponseBody
        public Map<String, Object> taskMessages(@PathVariable String taskId) {
            return Map.of("ok", true, "taskId", taskId);
        }

        @PostMapping("/status/api/tasks/sync")
        @ResponseBody
        public Map<String, Object> createTaskSync(@RequestBody Map<String, Object> body) {
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

        @PostMapping("/api/internal/legacy-probe")
        @ResponseBody
        public Map<String, Object> legacyProbe(@RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "body", body);
        }
    }
}
