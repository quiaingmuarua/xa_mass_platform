package com.xa.mass.api.internal;

import com.xa.mass.api.auth.ApiAuthService;
import com.xa.mass.api.auth.TaskSecurityViewSupport;
import com.xa.mass.api.sync.SyncTaskResultBridge;
import com.xa.mass.sdk.TaskAdminOperations;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalDebugTaskInvocationControllerTest {

    @Mock
    private TaskAdminOperations taskAdmin;

    @Mock
    private SyncTaskResultBridge syncBridge;

    @Test
    void internalDebugSyncRejectsSdkCredentialAttemptAtControllerBoundary() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new InternalDebugTaskInvocationController(
                taskAdmin,
                DefaultProjectEventCatalogFactory.createDefaultProjectRegistry(),
                new ApiAuthService(),
                new TaskSecurityViewSupport(),
                syncBridge
        )).build();

        mockMvc.perform(post("/internal/v1/debug/task-invocations:sync")
                        .header("X-Mass-User-Mode", "anonymous")
                        .header(SdkCredentialAuthSupport.API_KEY_HEADER, "sdk-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "project":"demoApp",
                                  "userId":"agent",
                                  "eventCode":"crawler.fetch-page",
                                  "items":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.msg").value(
                        "Internal debug sync invocation is operator-only; use public task APIs for SDK calls"));

        verify(taskAdmin, never()).createTaskShell(any());
        verify(taskAdmin, never()).appendTaskItemsWithReceipt(any(), any());
    }
}
