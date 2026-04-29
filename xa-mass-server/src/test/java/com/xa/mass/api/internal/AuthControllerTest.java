package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(new ApiAuthService())).build();
    }

    @Test
    void meReturnsViewerUserFromHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("ops-viewer"))
                .andExpect(jsonPath("$.data.permissions[0]").value("task:view"));
    }

    @Test
    void meReturnsCustomOperatorPrincipalFromHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "custom")
                        .header(ApiAuthService.USER_ID_HEADER, "alice")
                        .header(ApiAuthService.USER_NAME_HEADER, "Alice Ops")
                        .header(ApiAuthService.USER_EMAIL_HEADER, "alice@example.test")
                        .header(ApiAuthService.USER_ROLES_HEADER, "OPS_CUSTOM")
                        .header(ApiAuthService.USER_PERMISSIONS_HEADER, "task:view,worker:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value("alice"))
                .andExpect(jsonPath("$.data.name").value("Alice Ops"))
                .andExpect(jsonPath("$.data.email").value("alice@example.test"))
                .andExpect(jsonPath("$.data.roles[0]").value("OPS_CUSTOM"))
                .andExpect(jsonPath("$.data.permissions[0]").value("task:view"))
                .andExpect(jsonPath("$.data.permissions[1]").value("worker:view"));
    }

    @Test
    void logoutAcknowledgesAuthenticatedUser() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("ops-admin"));
    }
}
