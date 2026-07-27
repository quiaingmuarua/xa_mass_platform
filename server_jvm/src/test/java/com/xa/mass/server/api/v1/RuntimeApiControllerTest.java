package com.xa.mass.server.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskCreateRequest;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerUpsertRequest;
import com.xa.mass.server.kernelclient.KernelCommandClient;
import com.xa.mass.server.kernelclient.KernelClientException;
import com.xa.mass.server.kernelclient.KernelResponse;
import com.xa.mass.server.taskdata.TaskDataException;
import com.xa.mass.server.taskdata.TaskDataRuntime;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemAppendResult;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemAppendStatus;
import com.xa.mass.server.taskdata.TaskDataRuntime.TaskItemRecord;
import com.xa.mass.server.taskdata.TaskDataService;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RuntimeApiControllerTest {

    private RecordingKernelClient kernelClient;
    private RecordingTaskDataRuntime taskDataRuntime;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kernelClient = new RecordingKernelClient();
        taskDataRuntime = new RecordingTaskDataRuntime();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResourceCommandController(kernelClient),
                        new TaskControlController(kernelClient),
                        new TaskDataController(
                                new TaskDataService(taskDataRuntime)
                        )
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void exposesVersionedResourceCommands() throws Exception {
        mockMvc.perform(put("/api/v1/worker-groups/phone-tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "request-1")
                        .content("""
                                {
                                  "eventCodes": ["telecom.phone.inspect"],
                                  "itemAllocationFields": ["workerId"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "request-1"))
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(put(
                                "/api/v1/worker-groups/phone-tools/workers/worker-1"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "endpointManagerId": "endpoint-manager-1",
                                  "attributes": {"runtime": "java"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void exposesVersionedTaskCommandsAndWrapsItemResults() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "workerGroupId": "phone-tools",
                                  "taskType": "TASK_DRIVEN",
                                  "allocationRule": {},
                                  "config": {
                                    "priority": "0",
                                    "maximumCandidateWorkers": "1",
                                    "maxRetryTimes": "3"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("created"));

        mockMvc.perform(post("/api/v1/tasks/task-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("approved"));

        mockMvc.perform(post("/api/v1/tasks/task-1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("closed"));

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{
                                    "messageId": "message-1",
                                    "eventCode": "telecom.phone.inspect",
                                    "createdAtMillis": 1000,
                                    "payload": {"phoneNumber": "+14155552671"}
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-1.status")
                        .value("appended"));
        assertThat(taskDataRuntime.appendedItems).singleElement()
                .satisfies(item -> assertThat(item.priority()).isEqualTo(5));

        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messageIds":["message-1","message-2"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-1")
                        .value("{\"valid\":true}"))
                .andExpect(jsonPath("$.results.message-2").isEmpty());
    }

    @Test
    void malformedInputUsesThePublicErrorContract() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "bad-request")
                        .content("{\"taskId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.requestId").value("bad-request"));

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void resultQueryDeduplicatesIdsAndEnforcesItsBound() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageIds": [
                                    "message-2",
                                    "message-1",
                                    "message-2"
                                  ]
                                }
                                """))
                .andExpect(status().isOk());

        assertThat(taskDataRuntime.loadedMessageIds)
                .containsExactly("message-2", "message-1");

        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[]}"))
                .andExpect(status().isBadRequest());

        String tooManyIds = IntStream.range(0, 1_001)
                .mapToObj(index -> "\"message-" + index + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":" + tooManyIds + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingTaskResultQueryReturnsNotFound() throws Exception {
        taskDataRuntime.loadFailure = TaskDataException.notFound(
                "Task was not found"
        );

        mockMvc.perform(post("/api/v1/tasks/missing/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[\"message-1\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));
    }

    @Test
    void kernelTransportFailureUsesThePublicErrorContract() throws Exception {
        kernelClient.failure = KernelClientException.unavailable(
                new IllegalStateException("offline")
        );

        mockMvc.perform(post("/api/v1/tasks/task-1/approve")
                        .header("X-Request-Id", "unavailable-request"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("KERNEL_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId")
                        .value("unavailable-request"));
    }

    private static final class RecordingKernelClient
            implements KernelCommandClient {

        private RuntimeException failure;

        @Override
        public KernelResponse<CommandResultResponse> upsertWorkerGroup(
                String workerGroupId,
                WorkerGroupUpsertRequest request
        ) {
            return command(HttpStatus.OK, RuntimeCommandStatus.OK);
        }

        @Override
        public KernelResponse<CommandResultResponse> upsertWorker(
                String workerGroupId,
                String workerId,
                WorkerUpsertRequest request
        ) {
            return command(HttpStatus.OK, RuntimeCommandStatus.OK);
        }

        @Override
        public KernelResponse<CommandResultResponse> createTask(
                TaskCreateRequest request
        ) {
            return command(HttpStatus.CREATED, RuntimeCommandStatus.CREATED);
        }

        @Override
        public KernelResponse<CommandResultResponse> approveTask(String taskId) {
            if (failure != null) {
                throw failure;
            }
            return command(HttpStatus.OK, RuntimeCommandStatus.APPROVED);
        }

        @Override
        public KernelResponse<CommandResultResponse> closeTask(String taskId) {
            return command(HttpStatus.OK, RuntimeCommandStatus.CLOSED);
        }

        @Override
        public boolean isHealthy() {
            return true;
        }

        private static KernelResponse<CommandResultResponse> command(
                HttpStatus status,
                RuntimeCommandStatus commandStatus
        ) {
            return new KernelResponse<>(
                    status,
                    new CommandResultResponse(commandStatus, null)
            );
        }
    }

    private static final class RecordingTaskDataRuntime
            implements TaskDataRuntime {

        private List<String> loadedMessageIds;
        private List<TaskItemRecord> appendedItems;
        private RuntimeException loadFailure;

        @Override
        public Map<String, TaskItemAppendResult> appendTaskItems(
                String taskId,
                List<TaskItemRecord> items
        ) {
            appendedItems = List.copyOf(items);
            return Map.of(
                    "message-1",
                    new TaskItemAppendResult(TaskItemAppendStatus.APPENDED)
            );
        }

        @Override
        public Map<String, String> loadTaskItemSuccessResults(
                String taskId,
                List<String> messageIds
        ) {
            if (loadFailure != null) {
                throw loadFailure;
            }
            loadedMessageIds = List.copyOf(messageIds);
            var results = new java.util.LinkedHashMap<String, String>();
            results.put("message-1", "{\"valid\":true}");
            results.put("message-2", null);
            return results;
        }
    }
}
