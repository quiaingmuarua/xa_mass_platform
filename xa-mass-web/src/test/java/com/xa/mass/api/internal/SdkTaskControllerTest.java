package com.xa.mass.api.internal;

import com.xa.mass.base.enums.task.TaskIntakeStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.sdk.TaskOperations;
import com.xa.mass.sdk.catalog.DefaultProjectEventCatalogFactory;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.model.MassTaskRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SdkTaskControllerTest {

    @Mock
    private TaskOperations taskOperations;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProjectEventCatalog catalog = DefaultProjectEventCatalogFactory.createDefaultRegistry();
        mockMvc = MockMvcBuilders.standaloneSetup(new SdkTaskController(taskOperations, catalog, null)).build();
    }

    @Test
    void createSdkTaskMapsJsonPayloadAndSdkMetadata() throws Exception {
        Task createdTask = new Task();
        createdTask.setTid("task-sdk-001");
        when(taskOperations.createTask(any(MassTaskRequest.class))).thenReturn(createdTask);

        mockMvc.perform(post("/sdk/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "project": "demoApp",
                                  "taskName": "sdk-crawler",
                                  "eventCode": "crawler.fetch-page",
                                  "mode": "STREAMING",
                                  "payloadType": "JSON",
                                  "sharedConfig": {"site": "example"},
                                  "inputs": [{"url": "https://example.test"}],
                                  "batchSize": 1,
                                  "defaultMsgMaxRetryCount": 2,
                                  "maxRuntimeSeconds": 60
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value("task-sdk-001"));

        ArgumentCaptor<MassTaskRequest> captor = ArgumentCaptor.forClass(MassTaskRequest.class);
        verify(taskOperations).createTask(captor.capture());
        MassTaskRequest request = captor.getValue();

        org.junit.jupiter.api.Assertions.assertEquals("sdk-client", request.getUserId());
        org.junit.jupiter.api.Assertions.assertEquals("demoApp", request.getProject());
        org.junit.jupiter.api.Assertions.assertTrue(request.isStreaming());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                Map.of("type", "json", "data", Map.of("url", "https://example.test"))
        ), request.toEngineInputs());
        org.junit.jupiter.api.Assertions.assertEquals("crawler.fetch-page", request.getEventCode());
        org.junit.jupiter.api.Assertions.assertEquals("example", request.getSharedConfig().get("site"));
        org.junit.jupiter.api.Assertions.assertEquals(com.xa.mass.sdk.catalog.PayloadType.JSON, request.getPayloadType());
        org.junit.jupiter.api.Assertions.assertEquals(com.xa.mass.sdk.catalog.TaskMode.STREAMING, request.getMode());
    }

    @Test
    void createSdkTaskRejectsUnsupportedProjectEventBinding() throws Exception {
        mockMvc.perform(post("/sdk/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "project": "rcsApp",
                                  "taskName": "bad-event",
                                  "eventCode": "crawler.fetch-page",
                                  "mode": "SINGLE_RUN",
                                  "payloadType": "JSON",
                                  "inputs": [{"target": "x"}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        verify(taskOperations, never()).createTask(any(MassTaskRequest.class));
    }

    @Test
    void appendSdkItemsUsesStoredTextPayloadType() throws Exception {
        Task task = new Task();
        task.setTid("task-sdk-001");
        task.setIntakeStatus(TaskIntakeStatus.OPEN);
        task.setSharedConfig(Map.of("_sdk", Map.of(
                "eventCode", "chatbot.reply",
                "payloadType", "TEXT",
                "taskMode", "STREAMING"
        )));
        when(taskOperations.getTask("task-sdk-001")).thenReturn(task);
        when(taskOperations.appendTaskItems(any(), any())).thenReturn(2);

        mockMvc.perform(post("/sdk/tasks/task-sdk-001/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "inputs": ["hello", "world"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.added").value(2));

        verify(taskOperations).appendTaskItems("task-sdk-001", List.of(
                Map.of("type", "text", "text", "hello"),
                Map.of("type", "text", "text", "world")
        ));
    }

    @Test
    void sealSdkTaskDelegatesToSdkFacade() throws Exception {
        Task task = new Task();
        task.setTid("task-sdk-001");
        when(taskOperations.getTask("task-sdk-001")).thenReturn(task);
        when(taskOperations.sealTask("task-sdk-001")).thenReturn(true);

        mockMvc.perform(put("/sdk/tasks/task-sdk-001/seal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        verify(taskOperations).sealTask("task-sdk-001");
    }
}
