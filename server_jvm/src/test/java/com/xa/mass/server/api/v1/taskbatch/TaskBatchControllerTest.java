package com.xa.mass.server.api.v1.taskbatch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchInputUploadResponse;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchRunResponse;
import com.xa.mass.server.taskbatch.TaskBatchService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TaskBatchControllerTest {

    private TaskBatchService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TaskBatchService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                new TaskBatchController(service)
        ).build();
    }

    @Test
    void exposesUploadRunAndDownloadWithoutTaskCoordinates() throws Exception {
        when(service.upload(any(), any())).thenReturn(
                new TaskBatchInputUploadResponse("strings.txt", 11, 2)
        );
        when(service.run(any())).thenReturn(new TaskBatchRunResponse(
                "task-batch-1786680000123",
                "scenario-string-utils-workers",
                "extension.worker.string.md5",
                "value",
                "succeeded",
                "strings.txt",
                2,
                2,
                0,
                1,
                25,
                "task-batch-1786680000123.jsonl"
        ));
        when(service.download("task-batch-1786680000123.jsonl"))
                .thenReturn("{}\n".getBytes(StandardCharsets.UTF_8));

        mvc.perform(post("/api/v1/task-batches/input-files/strings.txt")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hello\nworld"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineCount").value(2));
        mvc.perform(post("/api/v1/task-batches/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerGroupId":"scenario-string-utils-workers",
                                  "eventCode":"extension.worker.string.md5",
                                  "payloadKey":"value",
                                  "inputFile":"strings.txt",
                                  "maximumWaitMillis":30000
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId")
                        .value("task-batch-1786680000123"))
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("taskId")
                )))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("workerId")
                )));
        mvc.perform(get(
                        "/api/v1/task-batches/output-files/"
                                + "task-batch-1786680000123.jsonl"
                ))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString(
                                "task-batch-1786680000123.jsonl"
                        )
                ))
                .andExpect(content().contentType("application/x-ndjson"));
    }

    @Test
    void doesNotExposeScenarioResourceRoutes() throws Exception {
        mvc.perform(get("/api/v1/task-batches/scenario-types"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/task-batches/scenarios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
