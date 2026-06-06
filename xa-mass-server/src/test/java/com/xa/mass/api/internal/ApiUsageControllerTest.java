package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthInterceptor;
import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.ApiAuthTestSupport;
import com.xa.mass.api.auth.ApiAuthorizationService;
import com.xa.mass.api.auth.ApiRouteAuthorizationCatalog;
import com.xa.mass.api.auth.apikey.ApiKeyCredentialService;
import com.xa.mass.api.auth.usage.ApiUsageLedgerService;
import com.xa.mass.api.auth.usage.ApiUsageOperation;
import com.xa.mass.api.auth.usage.ApiUsageStatus;
import com.xa.mass.api.auth.usage.InMemoryApiUsageLedgerStore;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ApiUsageControllerTest {

    private InMemorySubmitterOperations submitters;
    private ApiUsageLedgerService usageLedgerService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        submitters = new InMemorySubmitterOperations();
        usageLedgerService = new ApiUsageLedgerService(new InMemoryApiUsageLedgerStore());
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(
                ApiAuthTestSupport.defaultOperatorAuthService(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new ApiAuthorizationService(),
                new ApiRouteAuthorizationCatalog()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(new ApiUsageController(usageLedgerService, submitters))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void submitterCanReadOwnApiKeyUsage() throws Exception {
        submitters.registerSubmitter(com.xa.mass.sdk.auth.SubmitterRegistration.builder()
                .principalId("crawler-key")
                .credential("raw-secret")
                .userId("ops-admin")
                .permissions(List.of("task:create", "task:view"))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-usage-1"))
                .build());
        usageLedgerService.recordAccepted(
                submitters.authenticate("raw-secret"),
                ApiUsageOperation.TASK_CREATE,
                "crawlerApp",
                null,
                "task-001",
                null,
                null,
                1
        );

        mockMvc.perform(get("/api/v1/api-keys:current/usage")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "raw-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keyId").value("ak-usage-1"))
                .andExpect(jsonPath("$.data.principalId").value("crawler-key"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].operation").value("TASK_CREATE"))
                .andExpect(jsonPath("$.data.items[0].units").value(1));
    }

    @Test
    void operatorCanReadApiKeyUsageWithUsagePermission() throws Exception {
        PrincipalContext principal = new PrincipalContext(
                "crawler-key",
                null,
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-usage-1")
        );
        usageLedgerService.recordAccepted(
                principal,
                ApiUsageOperation.TASK_CREATE,
                "crawlerApp",
                null,
                "task-001",
                null,
                null,
                1
        );

        mockMvc.perform(get("/api/v1/api-keys/ak-usage-1/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].keyId").value("ak-usage-1"));
    }

    @Test
    void operatorUsageQuerySupportsBoundedFiltering() throws Exception {
        PrincipalContext principal = new PrincipalContext(
                "crawler-key",
                null,
                "crawlerApp",
                List.of("task:create"),
                List.of("crawlerApp"),
                List.of("crawler.fetch-page"),
                Map.of(ApiKeyCredentialService.ATTR_KEY_ID, "ak-usage-1")
        );
        usageLedgerService.recordAccepted(
                principal,
                ApiUsageOperation.TASK_CREATE,
                "crawlerApp",
                null,
                "task-001",
                null,
                "req-create",
                1
        );
        usageLedgerService.recordRejected(
                principal,
                ApiUsageOperation.TASK_RESULT_READ,
                "crawlerApp",
                null,
                "task-002",
                null,
                "req-read"
        );
        usageLedgerService.recordFailedAfterAccept(
                principal,
                ApiUsageOperation.TASK_ITEM_SYNC_APPEND,
                "crawlerApp",
                "crawler.fetch-page",
                "task-003",
                "msg-003",
                "req-sync",
                "IllegalStateException: bridge failed",
                400
        );

        mockMvc.perform(get("/api/v1/api-keys/ak-usage-1/usage")
                        .param("operation", ApiUsageOperation.TASK_ITEM_SYNC_APPEND.name())
                        .param("status", ApiUsageStatus.FAILED_AFTER_ACCEPT.name())
                        .param("project", "crawlerApp")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].operation").value("TASK_ITEM_SYNC_APPEND"))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILED_AFTER_ACCEPT"))
                .andExpect(jsonPath("$.data.items[0].taskId").value("task-003"))
                .andExpect(jsonPath("$.data.items[0].failureReason").value("IllegalStateException: bridge failed"))
                .andExpect(jsonPath("$.data.items[0].failureStatus").value(400));
    }

    @Test
    void viewerCannotReadOperatorApiKeyUsage() throws Exception {
        mockMvc.perform(get("/api/v1/api-keys/ak-usage-1/usage")
                        .header(ApiAuthService.USER_MODE_HEADER, "viewer"))
                .andExpect(status().isForbidden());
    }
}
