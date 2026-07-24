package com.xa.mass.server.api.v1;

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
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
import com.xa.mass.server.api.v1.model.WorkerGroupUpsertRequest;
import com.xa.mass.server.api.v1.model.WorkerUpsertRequest;
import com.xa.mass.server.kernelclient.KernelCommandClient;
import com.xa.mass.server.kernelclient.KernelClientException;
import com.xa.mass.server.kernelclient.KernelResponse;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RuntimeApiControllerTest {

    private RecordingKernelClient kernelClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        kernelClient = new RecordingKernelClient();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResourceCommandController(kernelClient),
                        new TaskCommandController(kernelClient)
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
        public KernelResponse<TaskItemsAppendResponse> appendTaskItems(
                String taskId,
                TaskItemsAppendRequest request
        ) {
            return new KernelResponse<>(
                    HttpStatus.OK,
                    new TaskItemsAppendResponse(Map.of(
                            "message-1",
                            new CommandResultResponse(
                                    RuntimeCommandStatus.APPENDED,
                                    null
                            )
                    ))
            );
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
}
