package com.xa.mass.server.api.v1.runtimeview;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.server.api.ApiExceptionHandler;
import com.xa.mass.server.api.RequestIdFilter;
import com.xa.mass.server.runtimeview.RuntimeViewService;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class RuntimeViewControllerTest {

    private WorkerResourceCatalog workerCatalog;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workerCatalog = mock(WorkerResourceCatalog.class);
        LocalValidatorFactoryBean validator =
                new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new RuntimeViewController(
                                new RuntimeViewService(workerCatalog)
                        )
                )
                .setControllerAdvice(new ApiExceptionHandler())
                .setValidator(validator)
                .addFilters(new RequestIdFilter())
                .build();
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
                .andExpect(jsonPath("$.workers[0].attributes.runtime")
                        .value("java"))
                .andExpect(jsonPath(
                        "$.workers[0].platformAttributes.region"
                ).value("local"))
                .andExpect(jsonPath(
                        "$.workers[0].dynamicAttributeNames[0]"
                ).value("battery"))
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
                .andExpect(status().isNotFound())
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
                .andExpect(status().isUnprocessableEntity())
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
                eventCodes,
                Set.of("workerId")
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
                Map.of("region", "local"),
                Set.of("battery")
        );
    }
}
