package com.xa.mass.api.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.ApiAuthTestSupport;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiPermissionNames;
import com.xa.mass.api.auth.ApiRouteAuthorizationCatalog;
import com.xa.mass.api.auth.ApiSecurityScenario;
import com.xa.mass.api.auth.ApiUnauthenticatedException;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyApplicationStore;
import com.xa.mass.api.auth.apikey.InMemoryApiKeyCredentialStore;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.api.auth.session.InMemorySubmitterViewerSessionStore;
import com.xa.mass.api.auth.session.SubmitterViewerSessionService;
import com.xa.mass.api.auth.usage.ApiUsageLedgerService;
import com.xa.mass.api.auth.usage.ApiUsageOperation;
import com.xa.mass.api.auth.usage.InMemoryApiUsageLedgerStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SubmitterViewerSessionControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private SubmitterViewerSessionService sessionService;
    private ApiUsageLedgerService usageLedgerService;
    private ApiAuthorizationService authorizationService;

    @BeforeEach
    void setUp() {
        InMemorySubmitterOperations submitters = new InMemorySubmitterOperations();
        ApiKeyCredentialService apiKeyCredentialService = new ApiKeyCredentialService(
                new InMemoryApiKeyApplicationStore(),
                new InMemoryApiKeyCredentialStore(),
                InMemoryUserRolePermissionStore.bootstrapDefaults(),
                submitters
        );
        sessionService = new SubmitterViewerSessionService(
                new InMemorySubmitterViewerSessionStore(),
                submitters,
                apiKeyCredentialService
        );
        usageLedgerService = new ApiUsageLedgerService(new InMemoryApiUsageLedgerStore());
        authorizationService = new ApiAuthorizationService(submitters, null);
        authorizationService.setApiKeyCredentialService(apiKeyCredentialService);
        authorizationService.setSubmitterViewerSessionService(sessionService);
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(
                ApiAuthTestSupport.defaultOperatorAuthService(),
                objectMapper,
                authorizationService,
                new ApiRouteAuthorizationCatalog()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ApiKeyController(apiKeyCredentialService, submitters, sessionService),
                        new SubmitterViewerSessionController(sessionService),
                        new ApiUsageController(usageLedgerService, submitters, apiKeyCredentialService, sessionService))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void apiKeyCreatesViewerSessionThatAuthenticatesThroughApiKeySurface() throws Exception {
        String apiKey = createApiKey();

        MvcResult createdSession = mockMvc.perform(post("/api/v1/api-key-viewer-sessions")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.rawSecret").isString())
                .andExpect(jsonPath("$.data.session.sessionId").isString())
                .andExpect(jsonPath("$.data.session.credentialHash").doesNotExist())
                .andExpect(jsonPath("$.data.session.permissions[?(@=='" + ApiPermissionNames.TASK_VIEW + "')]").exists())
                .andExpect(jsonPath("$.data.session.permissions[?(@=='" + ApiPermissionNames.TASK_CREATE + "')]").doesNotExist())
                .andReturn();

        String sessionSecret = objectMapper.readTree(createdSession.getResponse().getContentAsString())
                .get("data")
                .get("rawSecret")
                .asText();

        mockMvc.perform(get("/api/v1/api-key-viewer-sessions/me")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, sessionSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalId").value("crawler-api-key"));

        mockMvc.perform(get("/api/v1/api-keys:current")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, sessionSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principalId").value("crawler-api-key"))
                .andExpect(jsonPath("$.data.attributes.submitterViewerSessionId").isString());

        usageLedgerService.recordAccepted(
                sessionService.authenticate(sessionSecret),
                ApiUsageOperation.TASK_CREATE,
                "crawlerApp",
                null,
                "task-001",
                null,
                "req-001",
                1
        );
        mockMvc.perform(get("/api/v1/api-keys:current/usage")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, sessionSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyId").isString())
                .andExpect(jsonPath("$.data.items[0].operation").value("TASK_CREATE"));

        mockMvc.perform(post("/api/v1/api-key-viewer-sessions:logout")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, sessionSecret))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revokedAt").exists());

        mockMvc.perform(get("/api/v1/api-key-viewer-sessions/me")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, sessionSecret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedSourceApiKeyInvalidatesViewerSession() throws Exception {
        MvcResult createdKey = createApiKeyResult();
        JsonNode keyData = objectMapper.readTree(createdKey.getResponse().getContentAsString()).get("data");
        String apiKey = keyData.get("rawSecret").asText();
        String keyId = keyData.get("credential").get("keyId").asText();
        MvcResult createdSession = mockMvc.perform(post("/api/v1/api-key-viewer-sessions")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionSecret = objectMapper.readTree(createdSession.getResponse().getContentAsString())
                .get("data")
                .get("rawSecret")
                .asText();

        mockMvc.perform(post("/api/v1/api-keys/" + keyId + ":revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "rotated"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/api-keys:current")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, sessionSecret))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerSessionCannotCreateNestedSession() throws Exception {
        String apiKey = createApiKey();
        MvcResult createdSession = mockMvc.perform(post("/api/v1/api-key-viewer-sessions")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionSecret = objectMapper.readTree(createdSession.getResponse().getContentAsString())
                .get("data")
                .get("rawSecret")
                .asText();

        mockMvc.perform(post("/api/v1/api-key-viewer-sessions")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, sessionSecret))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("submitter viewer sessions cannot create nested sessions"));
    }

    @Test
    void viewerSessionDoesNotAuthenticateAsExternalWorkerCredential() throws Exception {
        String apiKey = createApiKey();
        MvcResult createdSession = mockMvc.perform(post("/api/v1/api-key-viewer-sessions")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, apiKey))
                .andExpect(status().isCreated())
                .andReturn();
        String sessionSecret = objectMapper.readTree(createdSession.getResponse().getContentAsString())
                .get("data")
                .get("rawSecret")
                .asText();

        assertThrows(ApiUnauthenticatedException.class, () -> authorizationService.requireExternalWorkerCredential(
                sessionSecret,
                null,
                ApiSecurityScenario.WORKER_POLL,
                Map.of("workerId", "worker-001")
        ));
    }

    private String createApiKey() throws Exception {
        JsonNode data = objectMapper.readTree(createApiKeyResult().getResponse().getContentAsString()).get("data");
        return data.get("rawSecret").asText();
    }

    private MvcResult createApiKeyResult() throws Exception {
        return mockMvc.perform(post("/api/v1/api-keys")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "principalId", "crawler-api-key",
                                "createdForUserId", "ops-admin",
                                "projectScopes", List.of("crawlerApp"),
                                "eventScopes", List.of("crawler.fetch-page"),
                                "permissions", List.of(
                                        ApiPermissionNames.TASK_CREATE,
                                        ApiPermissionNames.TASK_VIEW,
                                        ApiPermissionNames.API_USAGE_VIEW),
                                "attributes", Map.of("label", "Crawler API key")
                        ))))
                .andExpect(status().isOk())
                .andReturn();
    }
}
