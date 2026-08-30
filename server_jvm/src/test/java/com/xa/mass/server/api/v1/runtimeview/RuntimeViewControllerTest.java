package com.xa.mass.server.api.v1.runtimeview;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreBand;
import com.xa.mass.kernel.score.TaskScoreBandCore.TaskScoreState;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.api.v1.runtimeview.model.WorkerNetworkObserveResponse;
import com.xa.mass.server.runtimeview.RuntimeViewService;
import com.xa.mass.server.runtimeview.WorkerNetworkObservationService;
import com.xa.mass.server.workerscheduling.WorkerSchedulingService;
import com.xa.mass.server.workerscheduling.WorkerSchedulingService.SchedulingState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.context.request.async.DeferredResult;

class RuntimeViewControllerTest {

    private WorkerResourceCatalog workerCatalog;
    private TaskResourceCatalog taskCatalog;
    private TaskScoreBandCore taskScores;
    private WorkerSchedulingService workerScheduling;
    private WorkerNetworkObservationService workerNetwork;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        taskCatalog = mock(TaskResourceCatalog.class);
        taskScores = mock(TaskScoreBandCore.class);
        workerScheduling = mock(WorkerSchedulingService.class);
        workerNetwork = mock(WorkerNetworkObservationService.class);
        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RuntimeViewController(
                                new RuntimeViewService(
                                        workerCatalog,
                                        taskCatalog,
                                        taskScores,
                                        workerScheduling
                                ),
                                workerNetwork
                        )
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    void taskPreviewPreservesScoreOrderAndMissingDescriptors()
            throws Exception {
        when(taskScores.previewScoreStates(100)).thenReturn(List.of(
                scoreState("task-review", TaskScoreBand.PRE_REVIEW),
                initialScoreState("missing-task"),
                scoreState("task-group-missing", TaskScoreBand.RUNNING_VISIBLE),
                scoreState("task-terminal", TaskScoreBand.TERMINAL)
        ));
        List<String> taskIds = List.of(
                "task-review",
                "missing-task",
                "task-group-missing",
                "task-terminal"
        );
        var tasks = new LinkedHashMap<String, TaskDescriptor>();
        tasks.put("task-review", task("task-review", "group-b"));
        tasks.put("missing-task", null);
        tasks.put(
                "task-group-missing",
                task("task-group-missing", "missing-group")
        );
        tasks.put("task-terminal", task("task-terminal", "group-a"));
        when(taskCatalog.loadTaskAllocationDescriptors(taskIds))
                .thenReturn(tasks);

        var groups = new LinkedHashMap<String, WorkerGroupDescriptor>();
        groups.put("group-b", group(
                "group-b",
                Map.of("capability", "beta"),
                Set.of("event.b")
        ));
        groups.put("missing-group", null);
        groups.put("group-a", group(
                "group-a",
                Map.of("capability", "alpha"),
                Set.of("event.a")
        ));
        when(workerCatalog.getWorkerGroupDescriptors(
                List.of("group-b", "missing-group", "group-a")
        )).thenReturn(groups);

        mockMvc.perform(post(
                        "/api/v1/runtime-view/tasks:preview"
                ).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "task-preview-request")
                        .content("{\"sampleLimit\":100}"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Request-Id",
                        "task-preview-request"
                ))
                .andExpect(jsonPath("$.sampleLimit").value(100))
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.entries[0].taskId")
                        .value("task-review"))
                .andExpect(jsonPath("$.entries[0].scoreBand")
                        .value("pre_review"))
                .andExpect(jsonPath(
                        "$.entries[0].workerGroup.attributes.capability"
                ).value("beta"))
                .andExpect(jsonPath(
                        "$.entries[0].task.workerAllocationMechanism"
                ).value("ON_DEMAND_ITEM_RULE"))
                .andExpect(jsonPath("$.entries[0].task.idleDisposition")
                        .value("PARK_WHEN_IDLE"))
                .andExpect(jsonPath(
                        "$.entries[0].task.config.maxRetryTimes"
                ).value("3"))
                .andExpect(jsonPath("$.entries[1].task")
                        .value(nullValue()))
                .andExpect(jsonPath("$.entries[1].workerGroup")
                        .value(nullValue()))
                .andExpect(jsonPath("$.entries[1].scoreBand")
                        .value("running-initial"))
                .andExpect(jsonPath("$.entries[2].task.taskId")
                        .value("task-group-missing"))
                .andExpect(jsonPath("$.entries[2].workerGroup")
                        .value(nullValue()))
                .andExpect(jsonPath("$.entries[3].scoreBand")
                        .value("terminal"))
                .andExpect(jsonPath("$.entries[0].score")
                        .doesNotExist())
                .andExpect(jsonPath("$.entries[0].timeMillis")
                        .doesNotExist())
                .andExpect(jsonPath("$.entries[0].suffix")
                        .doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.cursor").doesNotExist())
                .andExpect(jsonPath("$.hasMore")
                        .doesNotExist());

        var ordered = inOrder(taskScores, taskCatalog, workerCatalog);
        ordered.verify(taskScores).previewScoreStates(100);
        ordered.verify(taskCatalog).loadTaskAllocationDescriptors(taskIds);
        ordered.verify(workerCatalog).getWorkerGroupDescriptors(
                List.of("group-b", "missing-group", "group-a")
        );
    }

    @Test
    void emptyTaskPreviewSkipsDescriptorOwners()
            throws Exception {
        when(taskScores.previewScoreStates(25)).thenReturn(List.of());

        mockMvc.perform(post(
                        "/api/v1/runtime-view/tasks:preview"
                ).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sampleLimit\":25}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty());

        verifyNoInteractions(taskCatalog, workerCatalog);
    }

    @Test
    void taskPreviewRejectsIdentityDriftAsUnavailable()
            throws Exception {
        when(taskScores.previewScoreStates(100)).thenReturn(List.of(
                scoreState("task-a", TaskScoreBand.RUNNING_VISIBLE)
        ));
        when(taskCatalog.loadTaskAllocationDescriptors(
                List.of("task-a")
        )).thenReturn(Map.of(
                "task-a",
                task("another-task", "group-a")
        ));

        mockMvc.perform(post(
                        "/api/v1/runtime-view/tasks:preview"
                ).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "drift-request")
                        .content("{\"sampleLimit\":100}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(15002))
                .andExpect(jsonPath("$.requestId")
                        .value("drift-request"));
    }

    @Test
    void taskPreviewMapsOwnerFailureToUnavailable()
            throws Exception {
        when(taskScores.previewScoreStates(100))
                .thenThrow(new IllegalStateException("owner unavailable"));

        mockMvc.perform(post(
                        "/api/v1/runtime-view/tasks:preview"
                ).contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "owner-failure-request")
                        .content("{\"sampleLimit\":100}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(15002))
                .andExpect(jsonPath("$.requestId")
                        .value("owner-failure-request"));
    }

    @Test
    void taskPreviewRejectsOutOfRangeLimitsBeforeOwnerReads()
            throws Exception {
        for (int limit : List.of(0, 101)) {
            mockMvc.perform(post(
                            "/api/v1/runtime-view/tasks:preview"
                    ).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"sampleLimit\":" + limit + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(19001));
        }
        verifyNoInteractions(taskScores, workerCatalog, taskCatalog);
    }

    @Test
    void batchGetPreservesRequestedOrderAndReportsMissingGroups()
            throws Exception {
        List<String> requested = List.of(
                "group-b",
                "missing",
                "group-a"
        );
        var loaded =
                new LinkedHashMap<String, WorkerGroupDescriptor>();
        loaded.put(
                "group-b",
                group(
                        "group-b",
                        Map.of("runtime", "java"),
                        Set.of("event.b", "event.a")
                )
        );
        loaded.put("missing", null);
        loaded.put(
                "group-a",
                group(
                        "group-a",
                        Map.of("runtime", "python"),
                        Set.of("event.c")
                )
        );
        when(workerCatalog.getWorkerGroupDescriptors(requested))
                .thenReturn(loaded);

        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:batch-get"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "batch-request")
                        .content("""
                                {
                                  "workerGroupIds": [
                                    "group-b",
                                    "missing",
                                    "group-a"
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Request-Id",
                        "batch-request"
                ))
                .andExpect(jsonPath(
                        "$.workerGroups[0].workerGroupId"
                ).value("group-b"))
                .andExpect(jsonPath(
                        "$.workerGroups[0].eventCodes[0]"
                ).value("event.a"))
                .andExpect(jsonPath(
                        "$.workerGroups[1].workerGroupId"
                ).value("group-a"))
                .andExpect(jsonPath(
                        "$.missingWorkerGroupIds[0]"
                ).value("missing"))
                .andExpect(jsonPath(
                        "$.workerGroups[0].score"
                ).doesNotExist());
    }

    @Test
    void batchGetRejectsDuplicateBlankEmptyAndOversizedIds()
            throws Exception {
        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:batch-get"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerGroupIds":["group-a","group-a"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));
        verifyNoInteractions(workerCatalog);

        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:batch-get"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerGroupIds":[""]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:batch-get"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerGroupIds":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        String tooManyIds = IntStream.range(0, 21)
                .mapToObj(index -> "\"group-" + index + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:batch-get"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                "{\"workerGroupIds\":"
                                        + tooManyIds
                                        + "}"
                        ))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));
    }

    @Test
    void groupPreviewCountsUnreadableRowsWithoutListSemantics()
            throws Exception {
        var sampled = new LinkedHashMap<String, WorkerGroupDescriptor>();
        sampled.put("group-b", group(
                "group-b",
                Map.of("capability", "beta"),
                Set.of("event.b")
        ));
        sampled.put("broken", null);
        sampled.put("group-a", group(
                "group-a",
                Map.of("capability", "alpha"),
                Set.of("event.a")
        ));
        when(workerCatalog.sampleWorkerGroupDescriptors(100))
                .thenReturn(sampled);

        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sampleLimit\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampleLimit").value(100))
                .andExpect(jsonPath("$.sampledCount").value(3))
                .andExpect(jsonPath("$.returnedCount").value(2))
                .andExpect(jsonPath("$.unreadableCount").value(1))
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.workerGroups[0].workerGroupId")
                        .value("group-b"))
                .andExpect(jsonPath("$.workerGroups[1].workerGroupId")
                        .value("group-a"))
                .andExpect(jsonPath("$.cursor").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.hasMore").doesNotExist())
                .andExpect(jsonPath("$.complete").doesNotExist());

        verify(workerCatalog).sampleWorkerGroupDescriptors(100);
    }

    @Test
    void groupPreviewValidatesLimitAndMapsProviderFailure()
            throws Exception {
        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sampleLimit\":101}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));
        verifyNoInteractions(workerCatalog);

        when(workerCatalog.sampleWorkerGroupDescriptors(100))
                .thenThrow(new IllegalStateException("redis unavailable"));
        mockMvc.perform(post(
                                "/api/v1/runtime-view/"
                                        + "worker-groups:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "group-preview-request")
                        .content("{\"sampleLimit\":100}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(15002))
                .andExpect(jsonPath("$.requestId")
                        .value("group-preview-request"));
    }

    @Test
    void previewCountsUnreadableRowsAndExposesOnlyDescriptorFields()
            throws Exception {
        when(workerCatalog.getWorkerGroupDescriptors(
                List.of("group-a")
        )).thenReturn(groupLookup("group-a"));
        var sampled = new LinkedHashMap<String, WorkerDescriptor>();
        sampled.put("worker-b", worker("worker-b", "group-a"));
        sampled.put("broken", null);
        sampled.put("worker-a", worker("worker-a", "group-a"));
        when(workerCatalog.sampleWorkerDescriptors(
                "group-a",
                100
        )).thenReturn(sampled);

        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "group-a/workers:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sampleLimit":100,"filter":null}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workerGroupId")
                        .value("group-a"))
                .andExpect(jsonPath("$.sampleLimit").value(100))
                .andExpect(jsonPath("$.sampledCount").value(3))
                .andExpect(jsonPath("$.returnedCount").value(2))
                .andExpect(jsonPath("$.unreadableCount").value(1))
                .andExpect(jsonPath("$.generatedAt").isString())
                .andExpect(jsonPath("$.workers[0].workerId")
                        .value("worker-b"))
                .andExpect(jsonPath("$.workers[0].workerGroupId")
                        .value("group-a"))
                .andExpect(jsonPath(
                        "$.workers[0].endpointManagerId"
                ).value("endpoint-1"))
                .andExpect(jsonPath("$.workers[0].workerProperties.runtime")
                        .value("java"))
                .andExpect(jsonPath(
                        "$.workers[0].platformProperties.region"
                ).value("local"))
                .andExpect(jsonPath("$.workers[0].indexedProperties")
                        .doesNotExist())
                .andExpect(jsonPath("$.workers[0].score")
                        .doesNotExist())
                .andExpect(jsonPath("$.workers[0].lease")
                        .doesNotExist())
                .andExpect(jsonPath("$.workers[0].transportSession")
                        .doesNotExist())
                .andExpect(jsonPath("$.workers[0].payload")
                        .doesNotExist())
                .andExpect(jsonPath("$.cursor").doesNotExist())
                .andExpect(jsonPath("$.total").doesNotExist())
                .andExpect(jsonPath("$.hasMore").doesNotExist())
                .andExpect(jsonPath("$.complete").doesNotExist());

        var ordered = inOrder(workerCatalog);
        ordered.verify(workerCatalog).getWorkerGroupDescriptors(
                List.of("group-a")
        );
        ordered.verify(workerCatalog).sampleWorkerDescriptors(
                "group-a",
                100
        );
    }

    @Test
    void previewReturnsEmptySuccessForAnExistingGroup()
            throws Exception {
        when(workerCatalog.getWorkerGroupDescriptors(
                List.of("group-a")
        )).thenReturn(groupLookup("group-a"));
        when(workerCatalog.sampleWorkerDescriptors(
                "group-a",
                25
        )).thenReturn(Map.of());

        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "group-a/workers:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sampleLimit":25}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sampledCount").value(0))
                .andExpect(jsonPath("$.returnedCount").value(0))
                .andExpect(jsonPath("$.unreadableCount").value(0))
                .andExpect(jsonPath("$.workers").isEmpty());
    }

    @Test
    void previewValidatesGroupBeforeRejectingUnavailableFilter()
            throws Exception {
        var missing =
                new LinkedHashMap<String, WorkerGroupDescriptor>();
        missing.put("missing", null);
        when(workerCatalog.getWorkerGroupDescriptors(
                List.of("missing")
        )).thenReturn(missing);

        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "missing/workers:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleLimit":100,
                                  "filter":{"workerId":{"$eq":"worker-1"}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(15001));
        verify(workerCatalog, never()).sampleWorkerDescriptors(
                "missing",
                100
        );

        when(workerCatalog.getWorkerGroupDescriptors(
                List.of("group-a")
        )).thenReturn(groupLookup("group-a"));
        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "group-a/workers:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sampleLimit":100,
                                  "filter":{"workerId":{"$eq":"worker-1"}}
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(15003));
        verify(workerCatalog, never()).sampleWorkerDescriptors(
                "group-a",
                100
        );
    }

    @Test
    void previewMapsProviderFailureTo503AndKeepsRequestId()
            throws Exception {
        when(workerCatalog.getWorkerGroupDescriptors(
                List.of("group-a")
        )).thenReturn(groupLookup("group-a"));
        when(workerCatalog.sampleWorkerDescriptors(
                "group-a",
                100
        )).thenThrow(new IllegalStateException("redis unavailable"));

        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "group-a/workers:preview"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "preview-request")
                        .content("""
                                {"sampleLimit":100,"filter":null}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(
                        "X-Request-Id",
                        "preview-request"
                ))
                .andExpect(jsonPath("$.code").value(15002))
                .andExpect(jsonPath("$.message")
                        .value("Runtime View is unavailable"))
                .andExpect(jsonPath("$.requestId")
                        .value("preview-request"));
    }

    @Test
    void previewRejectsOutOfRangeSampleLimitsBeforeOwnerReads()
            throws Exception {
        for (int limit : List.of(0, 101)) {
            mockMvc.perform(post(
                                    "/api/v1/runtime-view/worker-groups/"
                                            + "group-a/workers:preview"
                            )
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                    "{\"sampleLimit\":"
                                            + limit
                                            + ",\"filter\":null}"
                            ))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value(19001));
        }
        verifyNoInteractions(workerCatalog);
    }

    @Test
    void schedulingObservationPreservesRequestedOrderAndHidesScore()
            throws Exception {
        var states = new LinkedHashMap<String, SchedulingState>();
        states.put("worker-2", SchedulingState.RECOVERY);
        states.put("worker-1", SchedulingState.HOT_SCORE_OVERDUE);
        when(workerScheduling.observe(
                "group-a",
                List.of("worker-2", "worker-1")
        )).thenReturn(new WorkerSchedulingService
                .WorkerSchedulingObservation(
                1_234L,
                states
        ));

        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "group-a/workers:scheduling-observe"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "scheduling-request")
                        .content("""
                                {"workerIds":["worker-2","worker-1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Request-Id",
                        "scheduling-request"
                ))
                .andExpect(jsonPath("$.workerGroupId")
                        .value("group-a"))
                .andExpect(jsonPath("$.readAt")
                        .value("1970-01-01T00:00:01.234Z"))
                .andExpect(jsonPath(
                        "$.statesByWorkerId.worker-2"
                ).value("recovery"))
                .andExpect(jsonPath(
                        "$.statesByWorkerId.worker-1"
                ).value("hot-score-overdue"))
                .andExpect(jsonPath("$.score").doesNotExist());

        verify(workerScheduling).observe(
                "group-a",
                List.of("worker-2", "worker-1")
        );
    }

    @Test
    void schedulingObservationRejectsDuplicateWorkersBeforeKernelCall()
            throws Exception {
        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "group-a/workers:scheduling-observe"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workerIds":["worker-1","worker-1"]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(19001));

        verifyNoInteractions(workerScheduling);
    }

    @Test
    void schedulingObservationMapsKernelFailureToRuntimeViewUnavailable()
            throws Exception {
        when(workerScheduling.observe(
                "group-a",
                List.of("worker-1")
        )).thenThrow(new IllegalStateException("kernel unavailable"));

        mockMvc.perform(post(
                                "/api/v1/runtime-view/worker-groups/"
                                        + "group-a/workers:scheduling-observe"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "scheduling-request")
                        .content("""
                                {"workerIds":["worker-1"]}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(15002))
                .andExpect(jsonPath("$.requestId")
                        .value("scheduling-request"));
    }

    @Test
    void networkObservationUsesTheAdapterScopedRuntimeProjection()
            throws Exception {
        var states = new LinkedHashMap<String, String>();
        states.put("worker-2", "disconnected");
        states.put("worker-1", "connected");
        DeferredResult<WorkerNetworkObserveResponse> deferred =
                new DeferredResult<>();
        deferred.setResult(new WorkerNetworkObserveResponse(
                "adapter-1",
                Instant.ofEpochMilli(1_234),
                states
        ));
        when(workerNetwork.observe(
                "adapter-1",
                List.of("worker-2", "worker-1"),
                "network-request"
        )).thenReturn(deferred);

        MvcResult observation = mockMvc.perform(post(
                                "/api/v1/runtime-view/endpoint-managers/"
                                        + "adapter-1/workers:network-observe"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Request-Id", "network-request")
                        .content("""
                                {"workerIds":["worker-2","worker-1"]}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(observation))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "X-Request-Id",
                        "network-request"
                ))
                .andExpect(jsonPath("$.endpointManagerId")
                        .value("adapter-1"))
                .andExpect(jsonPath("$.readAt")
                        .value("1970-01-01T00:00:01.234Z"))
                .andExpect(jsonPath(
                        "$.statesByWorkerId.worker-2"
                ).value("disconnected"))
                .andExpect(jsonPath(
                        "$.statesByWorkerId.worker-1"
                ).value("connected"));

        verify(workerNetwork).observe(
                "adapter-1",
                List.of("worker-2", "worker-1"),
                "network-request"
        );
    }

    private static LinkedHashMap<String, WorkerGroupDescriptor> groupLookup(
            String workerGroupId
    ) {
        var result =
                new LinkedHashMap<String, WorkerGroupDescriptor>();
        result.put(
                workerGroupId,
                group(
                        workerGroupId,
                        Map.of("kind", "scenario"),
                        Set.of("event")
                )
        );
        return result;
    }

    private static WorkerGroupDescriptor group(
            String workerGroupId,
            Map<String, Object> attributes,
            Set<String> eventCodes
    ) {
        return new WorkerGroupDescriptor(
                workerGroupId,
                attributes,
                eventCodes
        );
    }

    private static WorkerDescriptor worker(
            String workerId,
            String workerGroupId
    ) {
        return new WorkerDescriptor(
                workerId,
                workerGroupId,
                "endpoint-1",
                Map.of("runtime", "java"),
                Map.of("region", "local")
        );
    }

    private static TaskDescriptor task(
            String taskId,
            String workerGroupId
    ) {
        return new TaskDescriptor(
                taskId,
                workerGroupId,
                WorkerAllocationMechanism.ON_DEMAND_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
    }

    private static TaskScoreState scoreState(
            String taskId,
            TaskScoreBand band
    ) {
        boolean terminal = band == TaskScoreBand.TERMINAL;
        return new TaskScoreState(
                taskId,
                terminal ? -1 : 1,
                band,
                terminal ? null : TaskScoreBandCore.NORMAL_TIME_MIN_MILLIS,
                terminal ? null : 0
        );
    }

    private static TaskScoreState initialScoreState(String taskId) {
        return new TaskScoreState(
                taskId,
                1,
                TaskScoreBand.RUNNING_VISIBLE,
                TaskScoreBandCore.INITIAL_TIME_MILLIS,
                TaskScoreBandCore.MAX_SUFFIX
        );
    }
}
