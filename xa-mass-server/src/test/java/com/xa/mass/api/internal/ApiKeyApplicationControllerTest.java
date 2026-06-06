package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthTestSupport;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.ApiRouteAuthorizationCatalog;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyApplicationStore;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyCredentialStore;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyApplicationControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InMemorySubmitterOperations submitters = new InMemorySubmitterOperations();
        ApiKeyCredentialService service = new ApiKeyCredentialService(
                new InMemoryApiKeyApplicationStore(),
                new InMemoryApiKeyCredentialStore(),
                InMemoryUserRolePermissionStore.bootstrapDefaults(),
                submitters
        );
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(
                ApiAuthTestSupport.defaultOperatorAuthService(),
                objectMapper,
                new ApiAuthorizationService(),
                new ApiRouteAuthorizationCatalog()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ApiKeyApplicationController(service),
                        new ApiKeyController(service, submitters))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void applicationCanBeApprovedIntoWorkingApiKey() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/api-key-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestedPrincipalId", "approved-crawler-key",
                                "requestedUserId", "ops-admin",
                                "requestedProjectScopes", List.of("crawlerApp"),
                                "requestedEventScopes", List.of("crawler.fetch-page"),
                                "requestedPermissions", List.of(ApiPermissionNames.TASK_CREATE, ApiPermissionNames.TASK_VIEW),
                                "purpose", "crawler integration test"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();

        String applicationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("data").get("applicationId").asText();

        mockMvc.perform(get("/api/v1/api-key-applications/" + applicationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.applicationId").value(applicationId));

        MvcResult approved = mockMvc.perform(post("/api/v1/api-key-applications/" + applicationId + ":approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawSecret").isString())
                .andExpect(jsonPath("$.data.credential.applicationId").value(applicationId))
                .andExpect(jsonPath("$.data.credential.principalId").value("approved-crawler-key"))
                .andReturn();

        JsonNode approvedData = objectMapper.readTree(approved.getResponse().getContentAsString()).get("data");
        String rawSecret = approvedData.get("rawSecret").asText();

        mockMvc.perform(get("/api/v1/api-keys:current")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, rawSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalId").value("approved-crawler-key"))
                .andExpect(jsonPath("$.data.projectScopes[0]").value("crawlerApp"));
    }

    @Test
    void rejectedApplicationCannotBeApproved() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/api-key-applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestedPrincipalId", "rejected-key",
                                "requestedUserId", "ops-admin",
                                "requestedPermissions", List.of(ApiPermissionNames.TASK_CREATE),
                                "purpose", "bad request"
                        ))))
                .andExpect(status().isOk())
                .andReturn();

        String applicationId = objectMapper.readTree(created.getResponse().getContentAsString())
                .get("data").get("applicationId").asText();

        mockMvc.perform(post("/api/v1/api-key-applications/" + applicationId + ":reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "not enough detail"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/api-key-applications/" + applicationId + ":approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "too late"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("API key application is not pending: " + applicationId));
    }

    @Test
    void viewerCannotApproveApplication() throws Exception {
        mockMvc.perform(post("/api/v1/api-key-applications/app-1:approve")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "nope"))))
                .andExpect(status().isForbidden());
    }

}
