package com.xa.mass.api.internal;

import com.xa.mass.sdk.TaskOperations;
import com.xa.mass.sdk.auth.AuthProvider;
import com.xa.mass.sdk.auth.TaskSubmitterContext;
import com.xa.mass.sdk.model.MassTaskRequest;
import com.xa.mass.base.model.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SdkTaskControllerTest {

    @Mock
    private TaskOperations taskOperations;

    @Mock
    private AuthProvider authProvider;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SdkTaskController(taskOperations, authProvider)).build();
    }

    @Test
    void sdkTaskCreateUsesSubmitterScopeAndDelegatesToSdkTaskRequest() throws Exception {
        TaskSubmitterContext submitter = new TaskSubmitterContext(
                "telegram-bot",
                "bot-user",
                "telegramApp",
                Map.of("channel", "telegram")
        );
        Task createdTask = new Task();
        createdTask.setTid("sdk-task-001");
        createdTask.setProject("telegramApp");
        createdTask.setUser(com.xa.mass.base.model.UserRef.of("bot-user"));

        when(authProvider.authenticate("dev-api-key")).thenReturn(submitter);
        when(taskOperations.createTask(any(MassTaskRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/sdk/tasks")
                        .header("X-Mass-Api-Key", "dev-api-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"bot-reply",
                                  "eventCode":"chatbot.reply",
                                  "mode":"STREAMING",
                                  "payloadType":"TEXT",
                                  "inputs":["hello"],
                                  "sharedConfig":{"channel":"telegram"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("sdk-task-001"))
                .andExpect(jsonPath("$.data.project").value("telegramApp"))
                .andExpect(jsonPath("$.data.userId").value("bot-user"))
                .andExpect(jsonPath("$.data.principalId").value("telegram-bot"));

        ArgumentCaptor<MassTaskRequest> captor = ArgumentCaptor.forClass(MassTaskRequest.class);
        verify(taskOperations).createTask(captor.capture());
        MassTaskRequest request = captor.getValue();
        assertEquals("bot-user", request.getUserId());
        assertEquals("telegramApp", request.getProject());
        assertEquals("bot-reply", request.getTaskName());
        assertEquals("chatbot.reply", request.getEventCode());
        assertTrue(request.isStreaming());
        assertEquals(List.of(Map.of("type", "text", "text", "hello")), request.toEngineInputs());
    }

    @Test
    void sdkTaskCreateAcceptsBearerCredential() throws Exception {
        TaskSubmitterContext submitter = new TaskSubmitterContext("crawler-agent", null, "crawlerApp", Map.of());
        Task createdTask = new Task();
        createdTask.setTid("sdk-task-002");
        createdTask.setProject("crawlerApp");
        createdTask.setUser(com.xa.mass.base.model.UserRef.of("crawler-agent"));

        when(authProvider.authenticate("bearer-key")).thenReturn(submitter);
        when(taskOperations.createTask(any(MassTaskRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/sdk/tasks")
                        .header("Authorization", "Bearer bearer-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"fetch-page",
                                  "project":"crawlerApp",
                                  "eventCode":"crawler.fetch-page",
                                  "payloadType":"JSON",
                                  "inputs":[{"url":"https://example.test"}]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("crawler-agent"));
    }

    @Test
    void sdkTaskCreateRejectsMissingCredential() throws Exception {
        mockMvc.perform(post("/sdk/tasks")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"bot-reply",
                                  "eventCode":"chatbot.reply",
                                  "inputs":["hello"]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void sdkTaskCreateRejectsProjectScopeViolation() throws Exception {
        TaskSubmitterContext submitter = new TaskSubmitterContext("telegram-bot", "bot-user", "telegramApp", Map.of());
        when(authProvider.authenticate("dev-api-key")).thenReturn(submitter);

        mockMvc.perform(post("/sdk/tasks")
                        .header("X-Mass-Api-Key", "dev-api-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"bot-reply",
                                  "project":"crawlerApp",
                                  "eventCode":"chatbot.reply",
                                  "inputs":["hello"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("Submitter project scope does not allow project: crawlerApp"));

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void sdkTaskCreateRejectsUserScopeViolation() throws Exception {
        TaskSubmitterContext submitter = new TaskSubmitterContext("telegram-bot", "bot-user", "telegramApp", Map.of());
        when(authProvider.authenticate("dev-api-key")).thenReturn(submitter);

        mockMvc.perform(post("/sdk/tasks")
                        .header("X-Mass-Api-Key", "dev-api-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "taskName":"bot-reply",
                                  "eventCode":"chatbot.reply",
                                  "userId":"another-user",
                                  "inputs":["hello"]
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("Submitter user scope does not allow userId: another-user"));

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
    }
}
