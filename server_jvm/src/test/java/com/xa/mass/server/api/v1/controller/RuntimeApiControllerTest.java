package com.xa.mass.server.api.v1.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;

import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.error.ServerErrorCode;

import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
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
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreState;
import com.xa.mass.kernel.score.TaskItemScoreBandCore.TaskItemScoreBand;
import com.xa.mass.server.task.TaskItemOutcomeProperties;
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
import com.xa.mass.kernel.task.TaskRuntime.TaskItemResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationResult;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationStatus;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.kernel.task.TaskLifecycleCommands;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskApprovalStatus;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskCloseResult;
import com.xa.mass.kernel.task.TaskLifecycleCommands.TaskCloseStatus;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskCreationService;
import com.xa.mass.server.task.TaskIdGenerator;
import com.xa.mass.server.task.TaskItemMapper;
import com.xa.mass.server.task.TaskLifecycleService;
import com.xa.mass.server.task.call.TaskRpcCallService;
import com.xa.mass.server.task.call.TaskCallSubmissionService;
import com.xa.mass.server.task.call.TaskRpcProperties;
import com.xa.mass.server.task.call.TaskRpcWaitRegistry;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.server.task.call.WorkerGroupTaskCallRegistrationService;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerRegistrationResult;
import com.xa.mass.server.worker.endpoint.WorkerEndpointDirectory.Endpoint;
import com.xa.mass.server.worker.endpoint.WorkerTransportType;
import com.xa.mass.server.worker.identity.WorkerIdentityService;
import com.xa.mass.server.worker.group.WorkerGroupRegistrationService;
import com.xa.mass.server.worker.preparation.WorkerPreparationService;
import com.xa.mass.server.worker.identity.WorkerRegistrationKind;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationResult;
import com.xa.mass.workermatching.WorkerMatchingCatalog.MutationStatus;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private WorkerEndpointDirectory workerEndpoints;
    private WorkerResourceCatalog workerCatalog;
    private WorkerMatchingCatalog matchingCatalog;
    private TaskRuntime taskRuntime;
    private TaskResourceCatalog taskCatalog;
    private TaskCallItemSubmission taskCallSubmission;
    private TaskItemScoreBandCore itemScores;
    private TaskLifecycleCommands taskLifecycle;
    private TaskResultsExportService taskResultsExport;
    private TaskRpcWaitRegistry taskRpcRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workerIdentity = mock(WorkerIdentityService.class);
        workerEndpoints = mock(WorkerEndpointDirectory.class);
        workerCatalog = mock(WorkerResourceCatalog.class);
        matchingCatalog = mock(WorkerMatchingCatalog.class);
        taskRuntime = mock(TaskRuntime.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        itemScores = mock(TaskItemScoreBandCore.class);
        taskLifecycle = mock(TaskLifecycleCommands.class);
        taskResultsExport = mock(TaskResultsExportService.class);

        when(workerCatalog.registerWorkerGroup(any()))
                .thenReturn(new RegistrationResult(RegistrationStatus.OK));
        when(workerCatalog.getWorkerGroupDescriptors(anyList()))
                .thenAnswer(invocation -> {
                    List<String> workerGroupIds = invocation.getArgument(0);
                    var descriptors = new LinkedHashMap<
                            String,
                            WorkerGroupDescriptor
                            >();
                    if (workerGroupIds.contains("phone-tools")) {
                        descriptors.put(
                                "phone-tools",
                                new WorkerGroupDescriptor(
                                        "phone-tools",
                                        Map.of(),
                                        Set.of("telecom.phone.inspect")
                                )
                        );
                    }
                    return descriptors;
                });
        when(workerIdentity.registerAll(any(), anyList()))
                .thenAnswer(call -> java.util.stream.IntStream.range(0, ((List<?>) call.getArgument(1)).size())
                        .mapToObj(i -> "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c" + (i + 1)).toList());
        var endpoint = new Endpoint( WorkerTransportType.WEBSOCKET,
                URI.create("ws://127.0.0.1:18083/connect"));
        when(workerEndpoints.defaultEndpointId(any())).thenReturn("scenario-websocket");
        when(workerEndpoints.find(any())).thenReturn(endpoint);
        when(workerCatalog.registerWorkers(any(), anyList(), any())).thenAnswer(call -> {
            Map<String, WorkerRegistrationResult> results = new LinkedHashMap<>();
            ((List<String>) call.getArgument(1)).forEach(id -> results.put(id,
                    new WorkerRegistrationResult(RegistrationStatus.OK, "scenario-websocket", null)));
            return results;
        });
        when(workerIdentity.registrationKey(any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1).toString());
        var preparedQuery=org.mockito.Mockito.mock(com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery.class);
        when(matchingCatalog.prepareTaskQueries(anyMap())).thenAnswer(call -> {
            Map<String,String> coordinates=call.getArgument(0);
            var result=new LinkedHashMap<String,com.xa.mass.kernel.assignment.WorkerCandidateIndex.TaskQuery>();
            coordinates.keySet().forEach(task -> result.put(task,preparedQuery)); return result;
        });
        when(matchingCatalog.patchWorkerPlatformProperties(
                any(),
                any(),
                any()
        )).thenReturn(new MutationResult(MutationStatus.APPLIED));
        when(matchingCatalog.bindTaskRule(any(), any(), any()))
                .thenReturn(new MutationResult(MutationStatus.APPLIED));
        when(taskRuntime.createTask(any()))
                .thenReturn(new TaskCreationResult(
                        TaskCreationStatus.CREATED
                ));
        when(taskLifecycle.approveTask("task-1"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.APPROVED,
                        null
                ));
        when(taskLifecycle.approveTask("scenario-rpc-phone-tools"))
                .thenReturn(new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED,
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
        when(taskRuntime.loadTaskItemResults(
                any(),
                anyList()
        )).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(1);
            var results = new LinkedHashMap<String, TaskItemResult>();
            ids.forEach(id -> results.put(
                    id,
                    "message-1".equals(id)
                            ? TaskItemResult.succeeded(
                                    "{\"valid\":true}"
                            )
                            : "message-failed".equals(id)
                                    ? TaskItemResult.failed()
                            : null
            ));
            return results;
        });
        taskCallSubmission = mock(TaskCallItemSubmission.class);
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
        TaskItemMapper taskItems = new TaskItemMapper();
        TaskDataService taskData = new TaskDataService(
                taskRuntime,
                taskCatalog,
                taskItems,
                itemScores,
                new TaskItemOutcomeProperties(Map.of(7, "delivered", 8, "read", 9, "replied")),
                matchingCatalog
        );
        TaskRpcProperties rpcProperties = rpcProperties();
        taskRpcRegistry = new TaskRpcWaitRegistry(rpcProperties);
        TaskRpcCallService taskRpc = new TaskRpcCallService(
                new TaskCallSubmissionService(taskCallSubmission, taskCatalog, taskItems, matchingCatalog),
                taskRuntime, taskRpcRegistry, rpcProperties
        );
        WorkerGroupTaskCallRegistrationService registrations =
                new WorkerGroupTaskCallRegistrationService(
                        workerCatalog,
                        taskCatalog,
                        taskRuntime,
                        taskLifecycle,
                        matchingCatalog
                );
        TaskCreationService taskCreation = new TaskCreationService(
                workerCatalog,
                matchingCatalog,
                taskRuntime,
                new TaskIdGenerator()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ResourceCommandController(
                                new WorkerResourceCommandService(matchingCatalog, mock(WorkerSchedulingService.class))
                        ),
                        new WorkerGroupRegistrationController(
                                new WorkerGroupRegistrationService(
                                        workerCatalog,
                                        registrations
                                )
                        ),
                        new WorkerPreparationController(
                                new WorkerPreparationService(workerIdentity, workerEndpoints, workerCatalog)
                        ),
                        new TaskControlController(
                                taskCreation,
                                new TaskLifecycleService(
                                        taskLifecycle,
                                        taskCatalog
                                )
                        ),
                        new TaskDataController(
                                taskData,
                                taskRpc,
                                taskResultsExport
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
                100_000,
                256,
                50,
                100,
                250
        );
    }

    @Test
    void exposesWorkerGroupRegistrationAndWorkerPreparation() throws Exception {
        mockMvc.perform(post("/api/v1/worker-groups/phone-tools:register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "request-1")
                        .content("""
                                {
                                  "eventCodes": ["telecom.phone.inspect"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "request-1"))
                .andExpect(jsonPath("$.workerGroupId").value("phone-tools"))
                .andExpect(jsonPath("$.taskId").value(
                        "scenario-rpc-phone-tools"
                ))
                .andExpect(jsonPath("$.status").value("registered"));

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:prepare"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "transportType":"WEBSOCKET",
                                  "workerProperties": {
                                    "clientWorkerKey": "installation-1",
                                    "runtime": "java",
                                    "region": "local"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerId").value(
                        "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1"
                ));
        verify(workerIdentity).registerAll(eq("phone-tools"), anyList());
        verify(workerCatalog).registerWorkers(eq("phone-tools"), anyList(), eq("scenario-websocket"));

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:prepare-batch"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                    {
                                      "workerKind":"SCENARIO_LAB",
                                      "transportType":"WEBSOCKET",
                                      "workerProperties":{
                                      "labInventoryKey":"workers-a.jsonl",
                                      "labInventoryLine":"1"
                                    }},
                                    {
                                      "workerKind":"SCENARIO_LAB",
                                      "transportType":"WEBSOCKET",
                                      "workerProperties":{
                                      "labInventoryKey":"workers-a.jsonl",
                                      "labInventoryLine":"2"
                                    }}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].workerId").exists())
                .andExpect(jsonPath("$[0].labWorkerKey")
                        .doesNotExist());

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:prepare-batch"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                    {
                                      "workerKind":"SCENARIO_LAB",
                                      "transportType":"WEBSOCKET",
                                      "workerProperties":{
                                      "labInventoryKey":"workers-a.jsonl",
                                      "labInventoryLine":"1"
                                    }},
                                    {
                                      "workerKind":"SCENARIO_LAB",
                                      "transportType":"WEBSOCKET",
                                      "workerProperties":{
                                      "labInventoryKey":"workers-a.jsonl",
                                      "labInventoryLine":"1"
                                    }}
                                ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(14001));

        mockMvc.perform(put("/api/v1/worker-groups/phone-tools")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventCodes\":[]}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:register"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientWorkerKey\":\"legacy\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch(
                                "/api/v1/worker-groups/phone-tools/workers/"
                                        + "worker-1/platform-properties"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pool\":\"batch\","
                                + "\"removed\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:register"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/workers/"
                                        + "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1"
                                        + ":bind"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());

    }

    @Test
    void mapsWorkerGroupDeclarationValidationToItsPublicErrorCode()
            throws Exception {
        mockMvc.perform(post("/api/v1/worker-groups/group-1:register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "group-request")
                        .content("{\"eventCodes\":[\"event\",\"event\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(15005))
                .andExpect(jsonPath("$.requestId").value("group-request"));

        mockMvc.perform(post("/api/v1/worker-groups/group-1:register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(15005));
    }

    @Test
    void workerPropertiesPatchUsesResourceBusinessErrors() throws Exception {
        when(matchingCatalog.patchWorkerPlatformProperties(
                "phone-tools", "missing-worker", Map.of("pool", "batch")
        )).thenReturn(new MutationResult(
                MutationStatus.NOT_FOUND,
                "private owner detail"
        ));

        mockMvc.perform(patch(
                                "/api/v1/worker-groups/phone-tools/workers/"
                                        + "missing-worker/platform-properties"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pool\":\"batch\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(15008))
                .andExpect(jsonPath("$.message")
                        .value("Worker resource was not found"))
                .andExpect(jsonPath("$..reason").doesNotExist());
    }

    @Test
    void workerPropertiesPatchReturnsUnchangedForOwnerNoop()
            throws Exception {
        when(matchingCatalog.patchWorkerPlatformProperties(
                "phone-tools", "worker-1", Map.of("pool", "batch")
        )).thenReturn(new MutationResult(MutationStatus.UNCHANGED));

        mockMvc.perform(patch(
                                "/api/v1/worker-groups/phone-tools/workers/"
                                        + "worker-1/platform-properties"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pool\":\"batch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());
    }


    @Test void createsNamedRuleAndUnrestrictedFiniteTasksWithoutCandidateCapacity() throws Exception {
        when(matchingCatalog.bindTaskRule(anyString(), anyString(), anyString())).thenReturn(
                new WorkerMatchingCatalog.MutationResult(WorkerMatchingCatalog.MutationStatus.APPLIED));
        mockMvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerGroupId\":\"phone-tools\",\"ruleId\":\"worker.country\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"workerGroupId\":\"phone-tools\"}"))
                .andExpect(status().isOk());
        var descriptors = ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime, org.mockito.Mockito.times(2)).createTask(descriptors.capture());
        assertThat(descriptors.getAllValues()).allSatisfy(task -> {
            assertThat(task.idleDisposition()).isEqualTo(TaskIdleDisposition.CLOSE_WHEN_IDLE);
            assertThat(task.config()).doesNotContainKey("maximumCandidateWorkers");
        });
        verify(matchingCatalog).bindTaskRule(anyString(), eq("phone-tools"), eq(WorkerMatchingCatalog.DEFAULT_RULE_ID));
    }

    @Test void rejectsMixedRulesAndIrrelevantCandidateCapacityBeforeOwners() throws Exception {
        for (String extra : List.of("\"ruleId\":\"worker.country\",\"allocationRule\":{}",
                "\"ruleId\":\"worker.country\",\"maximumCandidateWorkers\":1", "\"maximumCandidateWorkers\":10")) {
            mockMvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"workerGroupId\":\"phone-tools\","+extra+"}"))
                    .andExpect(status().isBadRequest());
        }
        verify(taskRuntime, org.mockito.Mockito.never()).createTask(any());
        verify(matchingCatalog, org.mockito.Mockito.never()).bindTaskRule(anyString(), anyString(), anyString());
    }

    @Test
    void exposesVersionedTaskCommandsAndDirectItemResults() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerGroupId": "phone-tools"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(matchesPattern(
                        "task-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                                + "[0-9a-f]{4}-[0-9a-f]{12}"
                )))
                .andExpect(jsonPath("$.status").doesNotExist());

        ArgumentCaptor<TaskDescriptor> descriptorCaptor =
                ArgumentCaptor.forClass(TaskDescriptor.class);
        verify(taskRuntime).createTask(descriptorCaptor.capture());
        assertThat(descriptorCaptor.getValue().workerGroupId())
                .isEqualTo("phone-tools");
        assertThat(descriptorCaptor.getValue().config()).containsExactlyInAnyOrderEntriesOf(
                Map.of(
                        "priority", "50",
                        "maxRetryTimes", "3"
                )
        );

        mockMvc.perform(post("/api/v1/tasks/task-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());

        mockMvc.perform(post("/api/v1/tasks/task-1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("applied"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andExpect(jsonPath("$.message").doesNotExist());

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{
                                    "messageId": "message-1",
                                    "eventCode": "telecom.phone.inspect",
                                    "payload": {"phoneNumber": "+14155552671"},
                                    "ttlMillis": 30000
                                  }]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message-1.status")
                        .value("applied"))
                .andExpect(jsonPath("$.message-1.code")
                        .doesNotExist())
                .andExpect(jsonPath("$.message-1.message")
                        .doesNotExist());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskItem>> itemCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(taskRuntime).appendItems(eq("task-1"), itemCaptor.capture());
        assertThat(itemCaptor.getValue()).singleElement()
                .satisfies(item -> {
                    assertThat(item.priority()).isEqualTo(5);
                    assertThat(item.createdAtMillis()).isPositive();
                    assertThat(item.expireAtMillis()).isEqualTo(
                            item.createdAtMillis() + 30_000
                    );
                });

        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"message-1\",\"message-2\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message-1.status")
                        .value("succeeded"))
                .andExpect(jsonPath(
                        "$.message-1.opaqueResultPayload"
                ).value("{\"valid\":true}"))
                .andExpect(jsonPath("$.message-2.status")
                        .value("not_observed"));
    }

    @Test
    void idempotentTaskActionsReturnUnchanged() throws Exception {
        when(taskLifecycle.approveTask("task-1")).thenReturn(
                new TaskApprovalResult(
                        TaskApprovalStatus.ALREADY_APPROVED,
                        null
                )
        );
        when(taskLifecycle.closeTask("task-1")).thenReturn(
                new TaskCloseResult(
                        TaskCloseStatus.ALREADY_CLOSED,
                        null
                )
        );

        mockMvc.perform(post("/api/v1/tasks/task-1/approve"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"));
        mockMvc.perform(post("/api/v1/tasks/task-1/close"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("unchanged"));
    }

    @Test
    void itemAppendUsesAppliedOrRejectedActionOutcomes()
            throws Exception {
        when(taskRuntime.appendItems(eq("task-1"), anyList()))
                .thenReturn(Map.of(
                        "message-invalid",
                        new TaskItemAppendResult(
                                TaskItemAppendStatus.INVALID,
                                "private invalid reason"
                        ),
                        "message-missing",
                        new TaskItemAppendResult(
                                TaskItemAppendStatus.NOT_FOUND,
                                "private missing reason"
                        ),
                        "message-retry",
                        new TaskItemAppendResult(
                                TaskItemAppendStatus.RETRYABLE,
                                "private retry reason"
                        )
                ));

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {"messageId":"message-invalid","eventCode":"event","payload":{}},
                                  {"messageId":"message-missing","eventCode":"event","payload":{}},
                                  {"messageId":"message-retry","eventCode":"event","payload":{}}
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message-invalid.status")
                        .value("rejected"))
                .andExpect(jsonPath("$.message-invalid.code")
                        .value(12001))
                .andExpect(jsonPath("$.message-missing.status")
                        .value("rejected"))
                .andExpect(jsonPath("$.message-missing.code")
                        .value(12002))
                .andExpect(jsonPath("$.message-retry.status")
                        .value("rejected"))
                .andExpect(jsonPath("$.message-retry.code")
                        .value(12003))
                .andExpect(jsonPath("$..reason").doesNotExist());
    }

    @Test
    void taskCreateSemanticErrorsReturnInvalidWithoutCallingOwner()
            throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerGroupId": "phone-tools",
                                  "priority": 100
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/tasks"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allocationRule\":{}}"))
                .andExpect(status().isNotFound());

        verify(taskRuntime, org.mockito.Mockito.never()).createTask(any());
    }

    @Test
    void taskCreateRequiresAnExistingWorkerGroup() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerGroupId":"missing"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12011));

        verify(taskRuntime, org.mockito.Mockito.never()).createTask(any());
    }

    @Test
    void taskCreateOwnerFailureUsesTheTaskErrorContract() throws Exception {
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(
                        TaskCreationStatus.RETRYABLE,
                        "Task owner is unavailable"
                )
        );

        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerGroupId":"phone-tools"
                                }
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(12003))
                .andExpect(jsonPath("$.message")
                        .value("Task Owner is unavailable"))
                .andExpect(jsonPath("$.taskId").doesNotExist());
    }

    @Test
    void taskCreateAndLifecycleConflictsUseDetailedTaskCodes()
            throws Exception {
        when(taskRuntime.createTask(any())).thenReturn(
                new TaskCreationResult(
                        TaskCreationStatus.CONFLICT,
                        "private owner reason"
                )
        );
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "workerGroupId":"phone-tools"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12009))
                .andExpect(jsonPath("$.message")
                        .value("Task operation conflicts with current state"));

        when(taskLifecycle.approveTask("task-1")).thenReturn(
                new TaskApprovalResult(
                        TaskApprovalStatus.CONFLICT,
                        "private lifecycle reason"
                )
        );
        mockMvc.perform(post("/api/v1/tasks/task-1/approve"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12009))
                .andExpect(jsonPath("$.reason").doesNotExist());
    }

    @Test
    void managedTaskRejectsLifecycleAndAppendButAllowsResultLoad()
            throws Exception {
        String taskId = "scenario-rpc-phone-tools";

        mockMvc.perform(post("/api/v1/tasks/{taskId}/approve", taskId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12008));
        mockMvc.perform(post("/api/v1/tasks/{taskId}/close", taskId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12008));
        mockMvc.perform(post("/api/v1/tasks/{taskId}/items", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{
                                  "messageId":"message-internal",
                                  "eventCode":"observe",
                                  "payload":{}
                                }]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12008));
        mockMvc.perform(post("/api/v1/tasks/{taskId}/results:load", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"message-internal\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.message-internal.status"
                ).value("not_observed"));

        verify(taskRuntime, org.mockito.Mockito.never())
                .appendItems(eq(taskId), anyList());
        verify(taskLifecycle, org.mockito.Mockito.never())
                .closeTask(taskId);
    }

    @Test
    void rpcCallReturnsAnExistingSuccessWithoutReadingItemState()
            throws Exception {
        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "task-call:register"
                        ))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools:register"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventCodes\":["
                                + "\"telecom.phone.inspect\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerGroupId")
                        .value("phone-tools"))
                .andExpect(jsonPath("$.taskId")
                        .value("scenario-rpc-phone-tools"))
                .andExpect(jsonPath("$.status")
                        .value("registered"));

        MvcResult async = mockMvc.perform(
                        post(
                                "/api/v1/tasks/"
                                        + "scenario-rpc-phone-tools/items:call"
                        )
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "items": [{
                                            "messageId": "message-1",
                                            "eventCode": "telecom.phone.inspect",
                                            "payload": {"phoneNumber": "+14155552671"},
                                            "workerSelector": {}
                                          }],
                                          "waitTimeoutMillis": 1000
                                        }
                                        """)
                )
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message-1.status")
                        .value("succeeded"))
                .andExpect(jsonPath("$.taskId").doesNotExist())
                .andExpect(jsonPath("$.workerId").doesNotExist())
                .andExpect(jsonPath("$.batchId").doesNotExist())
                .andExpect(jsonPath("$.messageId").doesNotExist())
                .andExpect(jsonPath(
                        "$.message-1.opaqueResultPayload"
                )
                        .value("{\"valid\":true}"));

        mockMvc.perform(post(
                                "/api/v1/tasks/"
                                        + "scenario-rpc-phone-tools/"
                                        + "results:load"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"message-1\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message-1.status")
                        .value("succeeded"))
                .andExpect(jsonPath(
                        "$.message-1.opaqueResultPayload"
                ).value("{\"valid\":true}"));

        verify(taskRuntime, times(2)).loadTaskItemResults(
                "scenario-rpc-phone-tools",
                List.of("message-1")
        );
        verify(taskRuntime, org.mockito.Mockito.never())
                .loadTaskItems(any(), anyList());

        when(taskCatalog.loadTaskAllocationDescriptors(
                List.of("missing")
        )).thenReturn(Map.of());
        mockMvc.perform(post("/api/v1/tasks/missing/items:call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{
                                    "messageId": "message-2",
                                    "eventCode": "event",
                                    "payload": {},
                                    "workerSelector": {}
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12002));

        mockMvc.perform(post("/api/v1/tasks/task-1/items:call")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{
                                    "messageId": "message-finite",
                                    "eventCode": "event",
                                    "payload": {},
                                    "workerSelector": {}
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12008));
    }

    @Test
    void selectorHttpBindingRejectsLegacyShapesAndNeverCoercesParameterTypes() throws Exception {
        for (String selector : List.of(
                "[]", "[\"workerId\",\"$eq\",\"worker\"]", "null", "\"worker\"",
                "{\"workerId\":\"worker\"}", "{\"workerId\":[]}",
                "{\"workerId\":[1]}", "{\"workerId\":[true]}", "{\"workerId\":[null]}",
                "{\"workerId\":[{}]}", "{\"workerId\":[[\"worker\"]]}",
                "{\" \":[\"x\"]}")) {
            mockMvc.perform(post("/api/v1/tasks/scenario-rpc-phone-tools/items:call")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"items":[{"messageId":"id","eventCode":"event","payload":{},
                                    "workerSelector":%s}],"waitTimeoutMillis":1}
                                    """.formatted(selector)))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(taskCallSubmission, taskCatalog);
    }

    @Test
    void acceptedTaskCallUsesHttp200ForNotObservedResults()
            throws Exception {
        MvcResult pending = mockMvc.perform(post(
                                "/api/v1/tasks/"
                                        + "scenario-rpc-phone-tools/items:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{
                                    "messageId": "message-2",
                                    "eventCode": "event",
                                    "payload": {},
                                    "workerSelector": {}
                                  }]
                                }
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        taskRpcRegistry.shutdown();
        mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message-2.status")
                        .value("not_observed"))
                .andExpect(jsonPath("$.message-2.code")
                        .doesNotExist());
    }

    @Test
    void removedWorkerGroupCallAndLoadRoutesRemainUnavailable()
            throws Exception {
        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "items:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{
                                    "messageId": "message-unregistered",
                                    "eventCode": "event",
                                    "payload": {},
                                    "workerSelector": {}
                                  }]
                                }
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "item-results:load"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[\"message-unregistered\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyItemAllocationRuleIsRejected() throws Exception {
        mockMvc.perform(post(
                                "/api/v1/tasks/"
                                        + "scenario-rpc-phone-tools/items:call"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "items": [{
                                    "messageId": "legacy-item-rule",
                                    "eventCode": "event",
                                    "payload": {},
                                    "allocationRule": {}
                                  }]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12001));
    }

    @Test
    void malformedInputUsesThePublicErrorContract() throws Exception {
        mockMvc.perform(post("/api/v1/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "bad-request")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001))
                .andExpect(jsonPath("$.requestId").value("bad-request"));

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[null]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{
                                  "messageId":"message-1",
                                  "eventCode":"event",
                                  "payload":{}
                                }]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageIds\":[\"message-1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(post(
                                "/api/v1/worker-groups/phone-tools/"
                                        + "workers:prepare-batch"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workers":[{
                                  "workerKind":"SCENARIO_LAB",
                                  "transportType":"WEBSOCKET",
                                  "workerProperties":{}
                                }]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(patch(
                                "/api/v1/worker-groups/phone-tools/workers/"
                                        + "worker-1/platform-properties"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"pool\":\"batch\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        String tooManyItems = IntStream.range(0, 101)
                .mapToObj(index -> """
                        {
                          "messageId":"message-%d",
                          "eventCode":"event",
                          "payload":{}
                        }
                        """.formatted(index))
                .collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(post("/api/v1/tasks/task-1/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooManyItems))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));
    }

    @Test
    void finiteTaskExportNotReadyUsesTheTaskErrorContract()
            throws Exception {
        when(taskResultsExport.export("task-1"))
                .thenThrow(new com.xa.mass.server.error.ServerException(
                        com.xa.mass.server.error.ServerErrorCode
                                .TASK_RESULTS_NOT_READY,
                        "taskResultsExport.requireTerminal",
                        null,
                        null
                ));

        mockMvc.perform(post("/api/v1/tasks/task-1/results:export")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12010))
                .andExpect(jsonPath("$.message")
                        .value("Task results are not ready"));
    }

    @Test
    void resultQueryDeduplicatesIdsAndEnforcesItsBound() throws Exception {
        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                    "message-2",
                                    "message-1",
                                    "message-2",
                                    "message-failed"
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message-1.status")
                        .value("succeeded"))
                .andExpect(jsonPath("$.message-failed.status")
                        .value("failed"))
                .andExpect(jsonPath("$.message-2.status")
                        .value("not_observed"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> idsCaptor =
                ArgumentCaptor.forClass(List.class);
        verify(taskRuntime).loadTaskItemResults(
                eq("task-1"),
                idsCaptor.capture()
        );
        assertThat(idsCaptor.getValue())
                .containsExactly(
                        "message-2",
                        "message-1",
                        "message-failed"
                );

        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[]"))
                .andExpect(status().isBadRequest());

        String tooManyIds = IntStream.range(0, 1_001)
                .mapToObj(index -> "\"message-" + index + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(post("/api/v1/tasks/task-1/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tooManyIds))
                .andExpect(status().isBadRequest());
    }

    @Test
    void itemStatesUseScoreSnapshotsAndApplicationNamesWithoutReadingResults() throws Exception {
        var states = new LinkedHashMap<String, TaskItemScoreState>();
        states.put("active", new TaskItemScoreState(11L, TaskItemScoreBand.ACTIVE, 1, 1_000, 2));
        states.put("generic", new TaskItemScoreState(22L, TaskItemScoreBand.TERMINAL, 3, 2_000, null));
        states.put("failed", new TaskItemScoreState(33L, TaskItemScoreBand.TERMINAL, 5, 3_000, null));
        states.put("success", new TaskItemScoreState(44L, TaskItemScoreBand.TERMINAL, 6, 4_000, null));
        states.put("read", new TaskItemScoreState(55L, TaskItemScoreBand.TERMINAL, 8, 5_000, null));
        states.put("missing", null);
        when(itemScores.getItemScoreStates(eq("task-1"), anyList())).thenReturn(states);
        mockMvc.perform(post("/api/v1/tasks/task-1/items:states")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"active\",\"generic\",\"failed\",\"success\",\"read\",\"missing\",\"active\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active.band").value("active"))
                .andExpect(jsonPath("$.active.tag").value(1))
                .andExpect(jsonPath("$.active.timeMillis").value(1_000))
                .andExpect(jsonPath("$.active.score").doesNotExist())
                .andExpect(jsonPath("$.active.remainingBudget").doesNotExist())
                .andExpect(jsonPath("$.active.outcomeName").doesNotExist())
                .andExpect(jsonPath("$.generic.band").value("terminal"))
                .andExpect(jsonPath("$.generic.tag").value(3))
                .andExpect(jsonPath("$.generic.outcomeName").doesNotExist())
                .andExpect(jsonPath("$.failed.outcomeName").value("failed"))
                .andExpect(jsonPath("$.success.outcomeName").value("succeeded"))
                .andExpect(jsonPath("$.read.outcomeName").value("read"))
                .andExpect(jsonPath("$.missing").value(org.hamcrest.Matchers.nullValue()));
        verify(itemScores).getItemScoreStates("task-1",
                List.of("active", "generic", "failed", "success", "read", "missing"));
        verify(taskCatalog).loadTaskAllocationDescriptors(List.of("task-1"));
        org.mockito.Mockito.verifyNoInteractions(taskRuntime);
    }

    @Test
    void itemStateQueriesRejectInvalidBatchesAndMapOwnerFailures() throws Exception {
        String oversized = IntStream.range(0, 101).mapToObj(i -> "\"item-" + i + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        for (String body : List.of("[]", "[null]", "[\" \"]", oversized)) {
            mockMvc.perform(post("/api/v1/tasks/task-1/items:states")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        org.mockito.Mockito.verifyNoInteractions(itemScores, taskCatalog, taskRuntime);
        when(itemScores.getItemScoreStates(eq("task-1"), anyList()))
                .thenThrow(new IllegalStateException("corrupt stored score"));
        mockMvc.perform(post("/api/v1/tasks/task-1/items:states")
                        .contentType(MediaType.APPLICATION_JSON).content("[\"item\"]"))
                .andExpect(status().isServiceUnavailable());
        when(taskCatalog.loadTaskAllocationDescriptors(List.of("missing"))).thenReturn(Map.of());
        mockMvc.perform(post("/api/v1/tasks/missing/items:states")
                        .contentType(MediaType.APPLICATION_JSON).content("[\"item\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12002));
        org.mockito.Mockito.verifyNoInteractions(taskRuntime);
    }

    @Test
    void missingTaskDataOperationsReturnTaskBusinessErrors()
            throws Exception {
        when(taskCatalog.loadTaskAllocationDescriptors(
                List.of("missing")
        )).thenReturn(new LinkedHashMap<>(Map.of()));

        mockMvc.perform(post("/api/v1/tasks/missing/results:load")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"message-1\"]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12002));
        mockMvc.perform(post("/api/v1/tasks/missing/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [{
                                  "messageId":"message-1",
                                  "eventCode":"event",
                                  "payload":{}
                                }]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Test
    void lifecycleRetryableResultUsesTheTaskErrorContract()
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
                .andExpect(jsonPath("$.code").value(12003))
                .andExpect(jsonPath("$.message")
                        .value("Task Owner is unavailable"));
    }

    private static TaskDescriptor descriptor(String taskId) {
        boolean scenarioRpc = taskId.startsWith("scenario-rpc-");
        return new TaskDescriptor(taskId, "phone-tools", scenarioRpc
                        ? TaskIdleDisposition.PARK_WHEN_IDLE
                        : TaskIdleDisposition.CLOSE_WHEN_IDLE, !scenarioRpc ? Map.of(
                        "priority", "0",
                        "maxRetryTimes", "3"
                ) : Map.of("priority", "0", "maxRetryTimes", "3"));
    }
}
