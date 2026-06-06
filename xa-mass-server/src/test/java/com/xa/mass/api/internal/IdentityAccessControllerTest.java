package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.CompositePrincipalDirectory;
import com.xa.mass.api.auth.DefaultOperatorPrincipalDirectory;
import com.xa.mass.api.auth.HeaderPrincipalContextFactory;
import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.ApiRouteAuthorizationCatalog;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyApplicationStore;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyCredentialStore;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.api.auth.iam.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class IdentityAccessControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private InMemoryUserRolePermissionStore store;
    private InMemorySubmitterOperations submitters;
    private ApiKeyCredentialService apiKeyCredentialService;

    @BeforeEach
    void setUp() {
        store = InMemoryUserRolePermissionStore.bootstrapDefaults();
        submitters = new InMemorySubmitterOperations();
        apiKeyCredentialService = new ApiKeyCredentialService(
                new InMemoryApiKeyApplicationStore(),
                new InMemoryApiKeyCredentialStore(),
                store,
                submitters
        );
        ApiAuthService apiAuthService = new ApiAuthService(
                new CompositePrincipalDirectory(List.of(new DefaultOperatorPrincipalDirectory(store))),
                new HeaderPrincipalContextFactory()
        );
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(
                apiAuthService,
                objectMapper,
                new ApiAuthorizationService(),
                new ApiRouteAuthorizationCatalog()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new IdentityAccessController(store, apiKeyCredentialService),
                        new ApiKeyController(apiKeyCredentialService, submitters),
                        new AuthController(apiAuthService))
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

    @Test
    void adminCanCreateAndDisableUser() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", "api-reviewer",
                                "displayName", "API Reviewer",
                                "email", "api-reviewer@example.internal"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value("api-reviewer"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(patch("/api/v1/users/api-reviewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", UserStatus.DISABLED.name(),
                                "displayName", "API Reviewer Disabled"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("API Reviewer Disabled"))
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
    }

    @Test
    void roleBindingAffectsViewerPermissionSnapshot() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", not(hasItem(ApiPermissionNames.API_KEY_APPROVE))));

        mockMvc.perform(post("/api/v1/users/ops-viewer/roles/API_KEY_REVIEWER"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.userId").value("ops-viewer"))
                .andExpect(jsonPath("$.data.roleId").value("API_KEY_REVIEWER"))
                .andExpect(jsonPath("$.data.grantedBy").value("ops-admin"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasItem(ApiPermissionNames.API_KEY_APPROVE)));

        mockMvc.perform(delete("/api/v1/users/ops-viewer/roles/API_KEY_REVIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.removed").value(true));

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", not(hasItem(ApiPermissionNames.API_KEY_APPROVE))));
    }

    @Test
    void adminCanCreateAndUpdateCustomRole() throws Exception {
        mockMvc.perform(post("/api/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleId", "TASK_SUBMITTER",
                                "name", "Task Submitter",
                                "description", "Can submit tasks",
                                "permissions", List.of(ApiPermissionNames.TASK_CREATE)
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.roleId").value("TASK_SUBMITTER"))
                .andExpect(jsonPath("$.data.systemRole").value(false))
                .andExpect(jsonPath("$.data.permissions", hasItem(ApiPermissionNames.TASK_CREATE)));

        mockMvc.perform(patch("/api/v1/roles/TASK_SUBMITTER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Task Operator",
                                "permissions", List.of(ApiPermissionNames.TASK_CREATE, ApiPermissionNames.TASK_VIEW)
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Task Operator"))
                .andExpect(jsonPath("$.data.systemRole").value(false))
                .andExpect(jsonPath("$.data.permissions", hasItem(ApiPermissionNames.TASK_VIEW)));
    }

    @Test
    void customRoleBindingAffectsPermissionSnapshot() throws Exception {
        mockMvc.perform(post("/api/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleId", "TASK_SUBMITTER",
                                "name", "Task Submitter",
                                "permissions", List.of(ApiPermissionNames.TASK_CREATE)
                        ))))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", not(hasItem(ApiPermissionNames.TASK_CREATE))));

        mockMvc.perform(post("/api/v1/users/ops-viewer/roles/TASK_SUBMITTER"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasItem(ApiPermissionNames.TASK_CREATE)));
    }

    @Test
    void userEditPermissionIsRequiredForMutations() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("userId", "viewer-created"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/roles")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleId", "VIEWER_CREATED_ROLE",
                                "name", "Viewer Created Role",
                                "permissions", List.of(ApiPermissionNames.TASK_VIEW)
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void userMutationRejectsNullBody() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(patch("/api/v1/users/ops-viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(patch("/api/v1/roles/OPS_VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void roleMutationRejectsUnknownPermission() throws Exception {
        mockMvc.perform(post("/api/v1/roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "roleId", "BAD_ROLE",
                                "name", "Bad Role",
                                "permissions", List.of("unknown:permission")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("unknown permission: unknown:permission"));
    }

    @Test
    void systemRoleCannotBeUpdated() throws Exception {
        mockMvc.perform(patch("/api/v1/roles/OPS_VIEWER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Broken Viewer",
                                "permissions", List.of(ApiPermissionNames.TASK_CREATE)
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("system roles cannot be updated"));
    }

    @Test
    void disablingUserDisablesOwnedApiKeysThroughCredentialProjection() throws Exception {
        ApiKeyCredentialService.CreatedApiKey created = apiKeyCredentialService.createOperatorKey(
                new ApiKeyCredentialService.CreateApiKeyCommand(
                        "viewer-owned-key",
                        "ops-viewer",
                        List.of("demoApp"),
                        List.of("chatbot.reply"),
                        List.of(ApiPermissionNames.TASK_CREATE, ApiPermissionNames.TASK_VIEW),
                        "ops-admin",
                        null,
                        Map.of(),
                        null,
                        null
                ));

        mockMvc.perform(get("/api/v1/api-keys:current")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, created.rawSecret()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalId").value("viewer-owned-key"));

        mockMvc.perform(patch("/api/v1/users/ops-viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", UserStatus.DISABLED.name()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(get("/api/v1/api-keys:current")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, created.rawSecret()))
                .andExpect(status().isUnauthorized());

        assertThat(apiKeyCredentialService.get(created.record().keyId()).status().name()).isEqualTo("DISABLED");
    }
}
