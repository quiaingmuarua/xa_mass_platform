package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.ApiRouteAuthorizationCatalog;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiKeyControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InMemorySubmitterOperations submitters = new InMemorySubmitterOperations();
        ApiKeyCredentialService service = new ApiKeyCredentialService(
                new com.xa.mass.api.auth.apikey.InMemoryApiKeyApplicationStore(),
                new InMemoryApiKeyCredentialStore(),
                InMemoryUserRolePermissionStore.bootstrapDefaults(),
                submitters
        );
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(
                new ApiAuthService(),
                objectMapper,
                new ApiAuthorizationService(),
                new ApiRouteAuthorizationCatalog()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ApiKeyController(service),
                        new CurrentSubmitterController(submitters))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void operatorCreatedApiKeyAuthenticatesAndRevocationDisablesProjection() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "principalId", "crawler-api-key",
                                "createdForUserId", "ops-admin",
                                "projectScopes", List.of("crawlerApp"),
                                "eventScopes", List.of("crawler.fetch-page"),
                                "permissions", List.of(ApiPermissionNames.TASK_CREATE, ApiPermissionNames.TASK_VIEW),
                                "attributes", Map.of("label", "Crawler API key")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawSecret").isString())
                .andExpect(jsonPath("$.data.credential.keyId").isString())
                .andExpect(jsonPath("$.data.credential.principalId").value("crawler-api-key"))
                .andExpect(jsonPath("$.data.credential.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.credential.credentialHash").doesNotExist())
                .andReturn();

        JsonNode data = objectMapper.readTree(created.getResponse().getContentAsString()).get("data");
        String rawSecret = data.get("rawSecret").asText();
        String keyId = data.get("credential").get("keyId").asText();
        assertThat(rawSecret).startsWith("mass_sk_");

        mockMvc.perform(get("/api/v1/submitters/me")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, rawSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalId").value("crawler-api-key"))
                .andExpect(jsonPath("$.data.userId").value("ops-admin"))
                .andExpect(jsonPath("$.data.permissions[?(@=='" + ApiPermissionNames.USER_VIEW + "')]").doesNotExist())
                .andExpect(jsonPath("$.data.projectScopes[0]").value("crawlerApp"))
                .andExpect(jsonPath("$.data.attributes.apiKeyId").value(keyId));

        mockMvc.perform(get("/api/v1/api-keys/" + keyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rawSecret").doesNotExist())
                .andExpect(jsonPath("$.data.credentialHash").doesNotExist())
                .andExpect(jsonPath("$.data.keyPrefix").isString());

        mockMvc.perform(post("/api/v1/api-keys/" + keyId + ":revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "rotated"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.revokeReason").value("rotated"));

        mockMvc.perform(get("/api/v1/submitters/me")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, rawSecret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCannotCreateApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "principalId", "viewer-created-key",
                                "createdForUserId", "ops-viewer",
                                "permissions", List.of(ApiPermissionNames.TASK_CREATE)
                        ))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createRejectsUnknownPermission() throws Exception {
        mockMvc.perform(post("/api/v1/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "principalId", "bad-permission-key",
                                "createdForUserId", "ops-admin",
                                "permissions", List.of("not-a-real-permission")
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("unknown permission: not-a-real-permission"));
    }

}
