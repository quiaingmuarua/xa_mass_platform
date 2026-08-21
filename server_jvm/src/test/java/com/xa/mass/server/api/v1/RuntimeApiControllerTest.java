package com.xa.mass.server.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeResult;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerRuntimeStatus;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalStatus;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskCloseResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskCloseStatus;
import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.TaskRpcCallService;
import com.xa.mass.server.taskdata.TaskRpcProperties;
import com.xa.mass.server.taskdata.TaskRpcWaitRegistry;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointBinding;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import com.xa.mass.server.workeridentity.WorkerIdentityService;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RuntimeApiControllerTest {

    private WorkerIdentityService workerIdentity;
    private WorkerBindingService workerBinding;
    private WorkerResourceCatalog workerCatalog;
    private TaskRuntime taskRuntime;
    private TaskResourceCatalog taskCatalog;
    private TaskLifecycleCommands taskLifecycle;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workerIdentity = mock(WorkerIdentityService.class);
        workerBinding = mock(WorkerBindingService.class);
        workerCatalog = mock(WorkerResourceCatalog.class);
        taskRuntime = mock(TaskRuntime.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        taskLifecycle = mock(TaskLifecycleCommands.class);

        when(workerCatalog.upsertWorkerGroup(any()))
                .thenReturn(new WorkerRuntimeResult(WorkerRuntimeStatus.OK));
        when(workerIdentity.register(any(), any()))
                .thenReturn("32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1");
        when(workerBinding.bind(any(), any(), any(), any()))
                .thenReturn(new WorkerEndpointBinding(
                        "scenario-websocket",
                        WorkerTransportType.WEBSOCKET,
                        URI.create("ws://127.0.0.1:18083/connect")
                ));
        when(workerCatalog.patchWorkerPlatformProperties(
                any(),
                any(),
                any()
        )).thenReturn(new WorkerRuntimeResult(WorkerRuntimeStatus.OK));
        when(taskRuntime.createTask(any()))
                .thenReturn(new TaskCreationResult(
                        TaskCreationStatus.CREATED
                ));
        when(taskLifecycle.approveTask("task-1"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.APPROVED,
                        null
                ));
        when(taskLifecycle.closeTask("task-1"))
                .thenReturn(new TaskCloseResult(
                        TaskCloseStatus.CLOSED,
                        null
                ));
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenAnswer(invocation -> {
                    List<String> ids = invocation.getArgument(0);
                    var descriptors =
                            new LinkedHashMap<String, TaskDescriptor>();
                    ids.forEach(id -> descriptors.put(
                            id,
                            descriptor(id)
                    ));
                    return descriptors;
                });
        when(taskRuntime.appendItems(any(), anyList()))
                .thenAnswer(invocation -> {
                    List<TaskItem> items = invocation.getArgument(1);
                    var results = new LinkedHashMap<
                            String,
                            TaskItemAppendResult
                            >();
                    items.forEach(item -> results.put(
                            item.messageId(),
                            new TaskItemAppendResult(
                                    TaskItemAppendStatus.APPENDED
                            )
                    ));
                    return results;
                });
        when(taskRuntime.loadTaskItemSuccessResults(
                any(),
                anyList()
        )).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            var results = new LinkedHashMap<String, String>();
            ids.forEach(id -> results.put(
                    id,
                    "message-1".equals(id)
                            ? "{\"valid\":true}"
                            : null
            ));
            return results;
        });
        TaskCallItemSubmission taskCallSubmission =
                mock(TaskCallItemSubmission.class);
        when(taskCallSubmission.submit(any(), anyList()))
                .thenAnswer(invocation -> {
                    List<TaskItem> items = invocation.getArgument(1);
                    var results = new LinkedHashMap<
                            String,
                            TaskItemAppendResult
                            >();
                    items.forEach(item -> results.put(
                            item.messageId(),
                            new TaskItemAppendResult(
                                    TaskItemAppendStatus.APPENDED
                            )
                    ));
                    return new TaskCallSubmissionResult(
                            TaskCallSubmissionStatus.SUBMITTED,
                            results,
                            null
                    );
                });

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        TaskDataService taskData = new TaskDataService(
                taskRuntime,
                taskCatalog
        );
        TaskRpcProperties rpcProperties = rpcProperties();
        TaskRpcCallService taskRpc = new TaskRpcCallService(
                taskCallSubmission,
                taskRuntime,
                new TaskRpcWaitRegistry(rpcProperties),
                rpcProperties
        );
        WorkerGroupTaskCatalog groupTasks = () -> Map.of(
                "phone-tools",
                "scenario-rpc-phone-tools"
        );
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResourceCommandController(workerCatalog),
                        new WorkerIdentityController(workerIdentity),
                        new WorkerBindingController(workerBinding),
                        new TaskControlController(
                                taskRuntime,
                                taskLifecycle
                        ),
                        new TaskDataController(taskData),
                        new WorkerGroupTaskController(
                                new WorkerGroupTaskCallService(
                                        groupTasks,
                                        taskRpc
                                )
                        )
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    private static TaskRpcProperties rpcProperties() {
        return new TaskRpcProperties(
                30_000,
                60_000,
                10_000,
                50,
                100,
                250
        );
    }

    @Test
    void exposesVersionedResourceCommands() throws Exception {
        mockMvc.perform(put("/api/v1/worker-groups/phone-tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "request-1")
                        .content("""
                                {
                                  "eventCodes": ["telecom.phone.inspect"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "request-1"))
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:register"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerProperties": {
                                    "clientWorkerKey": "installation-1",
                                    "runtime": "java"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(
                        "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1"
                ));
        verify(workerIdentity).register(
                "phone-tools",
                Map.of(
                        "clientWorkerKey",
                        "installation-1",
                        "runtime",
                        "java"
                )
        );

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/workers/"
                                        + "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1"
                                        + ":bind"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transportType":"WEBSOCKET",
                                  "workerProperties":{
                                    "clientWorkerKey":"installation-1",
                                    "region":"local"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transportType")
                        .value("WEBSOCKET"))
                .andExpect(jsonPath("$.endpointUri")
                        .value("ws://127.0.0.1:18083/connect"));
        verify(workerBinding).bind(
                "phone-tools",
                "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1",
                WorkerTransportType.WEBSOCKET,
                Map.of(
                        "clientWorkerKey",
                        "installation-1",
                        "region",
                        "local"
                )
        );

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:register"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientWorkerKey\":\"legacy\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(
                                "/api/v1/worker-groups/phone-tools/workers/"
                                        + "worker-1/platform-properties"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"pool\":\"batch\","
                                + "\"removed\":null}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:register"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

    }

    @Test
    void exposesVersionedTaskCommandsAndWrapsItemResults() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-1",
                                  "workerGroupId": "phone-tools",
                                  "profile": "FINITE_PRECOMPUTED",
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskItem>> itemCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(taskRuntime).appendItems(eq("task-1"), itemCaptor.capture());
        assertThat(itemCaptor.getValue()).singleElement()
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
    void taskCreateSemanticErrorsReturnInvalidWithoutCallingOwner()
            throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-invalid-profile",
                                  "workerGroupId": "phone-tools",
                                  "profile": "REUSABLE_DIRECT",
                                  "allocationRule": {},
                                  "config": {
                                    "priority": "0",
                                    "maximumCandidateWorkers": "1",
                                    "maxRetryTimes": "3"
                                  }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("invalid"));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskId": "task-invalid-config",
                                  "workerGroupId": "phone-tools",
                                  "profile": "FINITE_PRECOMPUTED",
                                  "allocationRule": {},
                                  "config": {
                                    "priority": "not-decimal",
                                    "maximumCandidateWorkers": "1",
                                    "maxRetryTimes": "3"
                                  }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("invalid"));

        verify(taskRuntime, org.mockito.Mockito.never()).createTask(any());
    }

    @Test
    void directAllocationAppendPassesOpaqueRulesToTheKernelMatcher()
            throws Exception {
        when(taskCatalog.loadTaskAllocationDescriptors(List.of("item-task")))
                .thenReturn(Map.of(
                        "item-task",
                        new TaskDescriptor(
                                "item-task",
                                "phone-tools",
                                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                                TaskIdleDisposition.PARK_WHEN_IDLE,
                                null,
                                Map.of(
                                        "priority", "0",
                                        "maximumCandidateWorkers", "1",
                                        "maxRetryTimes", "3"
                                )
                        )
                ));
        when(taskRuntime.appendItems(eq("item-task"), anyList()))
                .thenAnswer(invocation -> {
                    List<TaskItem> items = invocation.getArgument(1);
                    var results = new LinkedHashMap<
                            String,
                            TaskItemAppendResult
                            >();
                    items.forEach(item -> results.put(
                            item.messageId(),
                            new TaskItemAppendResult(
                                    TaskItemAppendStatus.APPENDED
                            )
                    ));
                    return results;
                });

        mockMvc.perform(post("/api/v1/tasks/item-task/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{
                                  "messageId":"message-1",
                                  "eventCode":"observe",
                                  "createdAtMillis":1000,
                                  "payload":{},
                                  "allocationRule":{
                                    "workerId":{"$eq":"worker-1"},
                                    "worker.region":{"$eq":"cn-east"},
                                    "platform.pool":{"$eq":"batch"}
                                  }
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-1.status")
                        .value("appended"));

        mockMvc.perform(post("/api/v1/tasks/item-task/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{
                                  "messageId":"message-2",
                                  "eventCode":"observe",
                                  "createdAtMillis":1000,
                                  "payload":{},
                                  "allocationRule":{
                                    "worker.region":{"$eq":"cn-east"}
                                  }
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-2.status")
                        .value("appended"));

        mockMvc.perform(post("/api/v1/tasks/item-task/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{
                                  "messageId":"message-empty",
                                  "eventCode":"observe",
                                  "createdAtMillis":1000,
                                  "payload":{},
                                  "allocationRule":{}
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-empty.status")
                        .value("appended"));

        String tooManyWorkerIds = IntStream.range(0, 101)
                .mapToObj(index -> "\"worker-" + index + "\"")
                .collect(Collectors.joining(","));
        mockMvc.perform(post("/api/v1/tasks/item-task/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{
                                  "messageId":"message-3",
                                  "eventCode":"observe",
                                  "createdAtMillis":1000,
                                  "payload":{},
                                  "allocationRule":{
                                    "workerId":{"$in":[%s]}
                                  }
                                }]}
                                """.formatted(tooManyWorkerIds)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-3.status")
                        .value("appended"));

        mockMvc.perform(post("/api/v1/tasks/item-task/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{
                                  "messageId":"message-4",
                                  "eventCode":"observe",
                                  "createdAtMillis":1000,
                                  "payload":{},
                                  "allocationRule":{
                                    "workerId":{"$eq":"worker-1"},
                                    "worker.region":{"$like":"cn-*"}
                                  }
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-4.status")
                        .value("appended"));

        mockMvc.perform(post("/api/v1/tasks/item-task/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{
                                  "messageId":"message-5",
                                  "eventCode":"observe",
                                  "createdAtMillis":1000,
                                  "payload":{}
                                }]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.message-5.status")
                        .value("invalid"));

        verify(taskRuntime, times(5))
                .appendItems(eq("item-task"), anyList());
        verify(workerCatalog, times(0))
                .getWorkerGroupDescriptors(anyList());
    }

    @Test
    void rpcCallReturnsAnExistingSuccessWithoutReadingItemState()
            throws Exception {
        MvcResult async = mockMvc.perform(
                        post("/api/v1/worker-groups/phone-tools/items:call")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "item": {
                                            "messageId": "message-1",
                                            "eventCode": "telecom.phone.inspect",
                                            "createdAtMillis": 1000,
                                            "payload": {"phoneNumber": "+14155552671"},
                                            "allocationRule": {}
                                          },
                                          "waitTimeoutMillis": 1000
                                        }
                                        """)
                )
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("succeeded"))
                .andExpect(jsonPath("$.taskId").doesNotExist())
                .andExpect(jsonPath("$.messageId").value("message-1"))
                .andExpect(jsonPath("$.opaqueResultPayload")
                        .value("{\"valid\":true}"));

        verify(taskRuntime).loadTaskItemSuccessResults(
                "scenario-rpc-phone-tools",
                List.of("message-1")
        );
        verify(taskRuntime, org.mockito.Mockito.never())
                .loadTaskItems(any(), anyList());

        mockMvc.perform(post(
                                "/api/v1/worker-groups/missing/items:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "item": {
                                    "messageId": "message-2",
                                    "eventCode": "event",
                                    "createdAtMillis": 1000,
                                    "payload": {},
                                    "allocationRule": {}
                                  }
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(15001));

        mockMvc.perform(post("/api/v1/tasks/task-1/items:call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void malformedInputUsesThePublicErrorContract() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "bad-request")
                        .content("{\"taskId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001))
                .andExpect(jsonPath("$.requestId").value("bad-request"));

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));
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

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> idsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(taskRuntime).loadTaskItemSuccessResults(
                eq("task-1"),
                idsCaptor.capture()
        );
        assertThat(idsCaptor.getValue())
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
        when(taskCatalog.loadTaskAllocationDescriptors(
                List.of("missing")
        )).thenReturn(new LinkedHashMap<>(Map.of()));

        mockMvc.perform(post("/api/v1/tasks/missing/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[\"message-1\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Test
    void lifecycleRetryableResultUsesTheTaskCommandContract()
            throws Exception {
        when(taskLifecycle.approveTask("task-1")).thenReturn(
                new TaskApprovalResult(
                        TaskApprovalStatus.RETRYABLE,
                        "Task lifecycle owner is unavailable"
                )
        );

        mockMvc.perform(post("/api/v1/tasks/task-1/approve")
                        .header("X-Request-Id", "unavailable-request"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("retryable"))
                .andExpect(jsonPath("$.reason")
                        .value("Task lifecycle owner is unavailable"));
    }

    private static TaskDescriptor descriptor(String taskId) {
        boolean scenarioRpc = taskId.startsWith("scenario-rpc-");
        return new TaskDescriptor(
                taskId,
                "phone-tools",
                scenarioRpc
                        ? WorkerAllocationMechanism.DIRECT_ITEM_RULE
                        : WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                scenarioRpc
                        ? TaskIdleDisposition.PARK_WHEN_IDLE
                        : TaskIdleDisposition.CLOSE_WHEN_IDLE,
                scenarioRpc ? null : Map.of(),
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
    }
}
