package com.xa.mass.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        mockMvc.perform(get("/api/v1/tasks")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void viewerCanReadQueueDiagnostics() throws Exception {
        mockMvc.perform(get("/api/v1/runtime/queues")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void anonymousUserCannotReachQueueDiagnostics() throws Exception {
        mockMvc.perform(get("/api/v1/runtime/queues")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void anonymousUserCannotReachSessionDiagnostics() throws Exception {
        mockMvc.perform(get("/api/v1/runtime/sessions")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void authenticatedUserCanLoadMe() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void authenticatedUserCanLoadProjectOptions() throws Exception {
        mockMvc.perform(get("/api/v1/runtime/config/projects")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void authenticatedUserWithoutWorkerViewCannotLoadProjectOptions() throws Exception {
        mockMvc.perform(get("/api/v1/runtime/config/projects")
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
    void anonymousUserCannotReachInternalSyncWithoutSdkCredential() throws Exception {
        mockMvc.perform(post("/internal/v1/debug/task-invocations:sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .content("""
                                {
                                  "taskName":"sdk-sync-task",
                                  "eventCode":"crawler.fetch-page",
                                  "items":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void sdkCredentialAttemptCanReachInternalSyncWithoutOperatorPermission() throws Exception {
        mockMvc.perform(post("/internal/v1/debug/task-invocations:sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "taskName":"sdk-sync-task",
                                  "eventCode":"crawler.fetch-page",
                                  "items":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void sdkCredentialAttemptCanReachTaskCreateWithoutOperatorPermission() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "taskName":"sdk-task",
                                  "eventCode":"crawler.fetch-page"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void sdkCredentialAttemptCanReachTaskListWithoutOperatorPermission() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void sdkCredentialAttemptCanReachTaskDetailWithoutOperatorPermission() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/task-001")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Controller
    static class ProtectedApiController {
        @GetMapping("/api/v1/tasks")
        @ResponseBody
        public Map<String, Object> tasks() {
            return Map.of("ok", true);
        }

        @PostMapping("/api/v1/tasks")
        @ResponseBody
        public Map<String, Object> createTask(@RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "body", body);
        }

        @GetMapping("/api/v1/tasks/{taskId}")
        @ResponseBody
        public Map<String, Object> taskDetail(@PathVariable String taskId) {
            return Map.of("ok", true, "taskId", taskId);
        }

        @PostMapping("/internal/v1/debug/task-invocations:sync")
        @ResponseBody
        public Map<String, Object> createTaskSync(@RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "body", body);
        }

        @GetMapping("/api/v1/runtime/queues")
        @ResponseBody
        public Map<String, Object> queueStatus() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/v1/runtime/sessions")
        @ResponseBody
        public Map<String, Object> sessionList() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/v1/auth/me")
        @ResponseBody
        public Map<String, Object> me() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/v1/runtime/config/projects")
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
