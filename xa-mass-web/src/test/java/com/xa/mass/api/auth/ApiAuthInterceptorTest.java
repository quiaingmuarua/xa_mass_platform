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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
    void viewerCannotCallWorkerEditEndpoint() throws Exception {
        mockMvc.perform(put("/status/api/workers/{workerId}/supported-projects", "worker-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer")
                        .content("""
                                {
                                  "supportedProjects": ["demoApp"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("Missing permission: worker:edit"));
    }

    @Test
    void authenticatedUserCanLoadMe() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
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

        @PutMapping("/status/api/workers/{workerId}/supported-projects")
        @ResponseBody
        public Map<String, Object> updateSupportedProjects(@PathVariable String workerId,
                                                           @RequestBody Map<String, Object> body) {
            return Map.of("workerId", workerId, "body", body);
        }

        @GetMapping("/api/auth/me")
        @ResponseBody
        public Map<String, Object> me() {
            return Map.of("ok", true);
        }
    }
}
