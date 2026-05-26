package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.ApiRouteAuthorizationCatalog;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityAccessControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InMemoryUserRolePermissionStore store = InMemoryUserRolePermissionStore.bootstrapDefaults();
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(
                new ApiAuthService(),
                new ObjectMapper(),
                new ApiAuthorizationService(),
                new ApiRouteAuthorizationCatalog()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new IdentityAccessController(store))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void adminCanReadUsersRolesAndPermissions() throws Exception {
        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value("ops-admin"));

        mockMvc.perform(get("/api/v1/roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roleId").value("API_KEY_REVIEWER"));

        mockMvc.perform(get("/api/v1/permissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@=='" + ApiPermissionNames.API_KEY_APPROVE + "')]").exists())
                .andExpect(jsonPath("$.data[?(@=='" + ApiPermissionNames.API_USAGE_VIEW + "')]").exists());
    }

    @Test
    void viewerCannotReadIamEndpointsWithoutUserOrRolePermission() throws Exception {
        mockMvc.perform(get("/api/v1/users")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/roles")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void missingUserReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/users/missing-user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }
}
