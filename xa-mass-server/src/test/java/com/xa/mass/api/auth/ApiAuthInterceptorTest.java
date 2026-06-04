package com.xa.mass.api.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.PrincipalContext;
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

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiAuthInterceptorTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AuthProvider authProvider = mock(AuthProvider.class);
        when(authProvider.authenticate("sdk-key")).thenReturn(PrincipalContext.builder()
                .principalId("sdk-reader")
                .projectScopes(java.util.List.of("demoApp"))
                .eventScopes(java.util.List.of("demo.dispatch"))
                .build());
        when(authProvider.authenticate("wildcard-sdk-key")).thenReturn(PrincipalContext.builder()
                .principalId("sdk-admin")
                .projectScopes(java.util.List.of(PrincipalContext.WILDCARD_SCOPE))
                .eventScopes(java.util.List.of(PrincipalContext.WILDCARD_SCOPE))
                .build());
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(
                ApiAuthTestSupport.defaultOperatorAuthService(),
                new ObjectMapper(),
                new ApiAuthorizationService(authProvider, null),
                new ApiRouteAuthorizationCatalog()
        );
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
    void sdkCredentialAttemptCannotReachInternalSyncWithoutOperatorPermission() throws Exception {
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
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void sdkCredentialAttemptCanReachTaskCreateWithoutOperatorPermission() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "project":"crawlerApp",
                                  "userId":"sdk-user",
                                  "sourceRef":"sdk-task",
                                  "executionSpec":{"batchSize":1}
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

    @Test
    void sdkCredentialAttemptCanReachTaskAppendRoutesWithoutOperatorPermission() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/task-001/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "eventCode":"demo.dispatch",
                                  "items":[{"id":"item-1"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(post("/api/v1/tasks/task-001/items:sync")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "eventCode":"demo.dispatch",
                                  "item":{"id":"item-1"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void sdkCredentialAttemptCanReachTaskStageEvidenceRoutesWithoutOperatorPermission() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/task-001/items/msg-001/stages/FETCH/evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key")
                        .content("""
                                {
                                  "stageVersion":2,
                                  "stageStatus":"DONE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/tasks/task-001/items/msg-001/stages")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/tasks/task-001/items/msg-001/stages/FETCH")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void viewerCanReachWorkerControlReadRoutesAndEditorCanRequestWorkerCommand() throws Exception {
        mockMvc.perform(get("/api/v1/runtime/workers/worker-001/state")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/runtime/workers/states")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/runtime/workers/worker-001/commands")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/runtime/workers/commands/cmd-001")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(post("/api/v1/runtime/workers/worker-001/commands")
                        .header(ApiAuthService.USER_MODE_HEADER, "custom")
                        .header(ApiAuthService.USER_ID_HEADER, "worker-editor")
                        .header(ApiAuthService.USER_NAME_HEADER, "Worker Editor")
                        .header(ApiAuthService.USER_PERMISSIONS_HEADER, ApiPermissionNames.WORKER_EDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commandType\":\"DRAIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void runtimeWorkerDataPlaneDuplicateRoutesAreNotAuthorized() throws Exception {
        mockMvc.perform(post("/api/v1/runtime/workers/worker-001/capability-reports")
                        .header(ApiAuthService.USER_MODE_HEADER, "custom")
                        .header(ApiAuthService.USER_ID_HEADER, "worker-editor")
                        .header(ApiAuthService.USER_NAME_HEADER, "Worker Editor")
                        .header(ApiAuthService.USER_PERMISSIONS_HEADER, ApiPermissionNames.WORKER_EDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"capabilityVersion\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/v1/runtime/workers/worker-001/state-reports")
                        .header(ApiAuthService.USER_MODE_HEADER, "custom")
                        .header(ApiAuthService.USER_ID_HEADER, "worker-editor")
                        .header(ApiAuthService.USER_NAME_HEADER, "Worker Editor")
                        .header(ApiAuthService.USER_PERMISSIONS_HEADER, ApiPermissionNames.WORKER_EDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateVersion\":1,\"state\":\"READY\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(post("/api/v1/runtime/workers/worker-001/commands/cmd-001/ack")
                        .header(ApiAuthService.USER_MODE_HEADER, "custom")
                        .header(ApiAuthService.USER_ID_HEADER, "worker-editor")
                        .header(ApiAuthService.USER_NAME_HEADER, "Worker Editor")
                        .header(ApiAuthService.USER_PERMISSIONS_HEADER, ApiPermissionNames.WORKER_EDIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACKED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void validSdkCredentialCanReachProjectRoutesWithoutOperatorHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/v1/projects/demoApp")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "sdk-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void invalidSdkCredentialCannotReachProjectRoutes() throws Exception {
        mockMvc.perform(get("/api/v1/projects")
                        .header(ApiAuthService.USER_MODE_HEADER, "anonymous")
                        .header("X-Mass-Api-Key", "missing-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
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

        @PostMapping("/api/v1/tasks/{taskId}/items")
        @ResponseBody
        public Map<String, Object> appendTaskItems(@PathVariable String taskId,
                                                   @RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "taskId", taskId, "body", body);
        }

        @PostMapping("/api/v1/tasks/{taskId}/items:sync")
        @ResponseBody
        public Map<String, Object> appendTaskItemSync(@PathVariable String taskId,
                                                      @RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "taskId", taskId, "body", body);
        }

        @GetMapping("/api/v1/projects")
        @ResponseBody
        public Map<String, Object> projectList() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/v1/projects/{projectCode}")
        @ResponseBody
        public Map<String, Object> project(@PathVariable String projectCode) {
            return Map.of("ok", true, "projectCode", projectCode);
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

        @PostMapping("/api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}/evidence")
        @ResponseBody
        public Map<String, Object> reportTaskStageEvidence(@PathVariable String taskId,
                                                           @PathVariable String messageId,
                                                           @PathVariable String stageName,
                                                           @RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "taskId", taskId, "messageId", messageId, "stageName", stageName, "body", body);
        }

        @GetMapping("/api/v1/tasks/{taskId}/items/{messageId}/stages")
        @ResponseBody
        public Map<String, Object> listTaskStages(@PathVariable String taskId,
                                                  @PathVariable String messageId) {
            return Map.of("ok", true, "taskId", taskId, "messageId", messageId);
        }

        @GetMapping("/api/v1/tasks/{taskId}/items/{messageId}/stages/{stageName}")
        @ResponseBody
        public Map<String, Object> getTaskStage(@PathVariable String taskId,
                                                @PathVariable String messageId,
                                                @PathVariable String stageName) {
            return Map.of("ok", true, "taskId", taskId, "messageId", messageId, "stageName", stageName);
        }

        @GetMapping("/api/v1/runtime/workers/{workerId}/state")
        @ResponseBody
        public Map<String, Object> workerState(@PathVariable String workerId) {
            return Map.of("ok", true, "workerId", workerId);
        }

        @GetMapping("/api/v1/runtime/workers/states")
        @ResponseBody
        public Map<String, Object> workerStates() {
            return Map.of("ok", true);
        }

        @PostMapping("/api/v1/runtime/workers/{workerId}/capability-reports")
        @ResponseBody
        public Map<String, Object> capabilityReport(@PathVariable String workerId,
                                                    @RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "workerId", workerId, "body", body);
        }

        @PostMapping("/api/v1/runtime/workers/{workerId}/state-reports")
        @ResponseBody
        public Map<String, Object> stateReport(@PathVariable String workerId,
                                               @RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "workerId", workerId, "body", body);
        }

        @PostMapping("/api/v1/runtime/workers/{workerId}/commands")
        @ResponseBody
        public Map<String, Object> requestWorkerCommand(@PathVariable String workerId,
                                                        @RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "workerId", workerId, "body", body);
        }

        @PostMapping("/api/v1/runtime/workers/{workerId}/commands/{commandId}/ack")
        @ResponseBody
        public Map<String, Object> acknowledgeWorkerCommand(@PathVariable String workerId,
                                                            @PathVariable String commandId,
                                                            @RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "workerId", workerId, "commandId", commandId, "body", body);
        }

        @GetMapping("/api/v1/runtime/workers/{workerId}/commands")
        @ResponseBody
        public Map<String, Object> listWorkerCommands(@PathVariable String workerId) {
            return Map.of("ok", true, "workerId", workerId);
        }

        @GetMapping("/api/v1/runtime/workers/commands/{commandId}")
        @ResponseBody
        public Map<String, Object> getWorkerCommand(@PathVariable String commandId) {
            return Map.of("ok", true, "commandId", commandId);
        }

        @PostMapping("/api/internal/legacy-probe")
        @ResponseBody
        public Map<String, Object> legacyProbe(@RequestBody Map<String, Object> body) {
            return Map.of("ok", true, "body", body);
        }
    }
}
