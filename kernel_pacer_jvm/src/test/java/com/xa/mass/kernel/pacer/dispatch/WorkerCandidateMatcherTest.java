package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerCandidateMatcherTest {

    @Test
    void filterUsesOneBoundedCanonicalLoadWithoutApplyingLimitsOrUniqueness() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerDescriptor east = worker("worker-east", "east");
        WorkerDescriptor west = worker("worker-west", "west");
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-east", "worker-west")
        )).thenReturn(Map.of(
                "worker-east", east,
                "worker-west", west
        ));
        LinkedHashMap<String, WorkerCandidateRequest> requests = requests();

        Map<String, List<String>> filtered =
                new WorkerCandidateMatcher(catalog)
                        .filterCandidateWorkerIds(
                                "group-1",
                                Map.of(
                                        "preferred",
                                        List.of("worker-east", "worker-west"),
                                        "fallback",
                                        List.of("worker-east", "worker-west")
                                ),
                                requests
                        );

        assertEquals(List.of("worker-east"), filtered.get("preferred"));
        assertEquals(
                List.of("worker-east", "worker-west"),
                filtered.get("fallback")
        );
        verify(catalog).getWorkerDescriptors(
                "group-1",
                List.of("worker-east", "worker-west")
        );
    }

    @Test
    void leasedMatchAppliesPriorityLimitsAndUniqueWorkerOwnership() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-east", "worker-west")
        )).thenReturn(Map.of(
                "worker-east", worker("worker-east", "east"),
                "worker-west", worker("worker-west", "west")
        ));
        Map<String, List<AcquiredWorkerCandidate>> matched =
                new WorkerCandidateMatcher(catalog)
                        .matchLeasedWorkerCandidates(
                                "group-1",
                                Map.of(
                                        "worker-east", 101L,
                                        "worker-west", 102L
                                ),
                                Map.of(
                                        "preferred",
                                        List.of("worker-east", "worker-west"),
                                        "fallback",
                                        List.of(
                                                "worker-east",
                                                "worker-west",
                                                "outside-input"
                                        )
                                ),
                                requests()
                        );

        assertEquals(
                List.of("worker-east"),
                matched.get("preferred").stream()
                        .map(AcquiredWorkerCandidate::workerId)
                        .toList()
        );
        assertEquals(
                List.of("worker-west"),
                matched.get("fallback").stream()
                        .map(AcquiredWorkerCandidate::workerId)
                        .toList()
        );
        assertEquals(
                "adapter-1",
                matched.get("preferred").getFirst().endpointManagerId()
        );
        assertEquals(
                "worker-east",
                matched.get("preferred").getFirst().workerId()
        );
        assertEquals(
                "group-1",
                matched.get("preferred").getFirst().workerGroupId()
        );
        assertEquals(
                101L,
                matched.get("preferred").getFirst().workerLeaseScore()
        );
    }

    @Test
    void acquiredCandidateRequiresIdentitiesAndCurrentEndpoint() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AcquiredWorkerCandidate(
                        " ", "group-1", "adapter-1", 101L
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new AcquiredWorkerCandidate(
                        "worker-1", "group-1", " ", 101L
                )
        );
    }

    private static LinkedHashMap<String, WorkerCandidateRequest> requests() {
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        requests.put("preferred", new WorkerCandidateRequest(
                0,
                1,
                Map.of("worker.region", Map.of("$eq", "east"))
        ));
        requests.put("fallback", new WorkerCandidateRequest(
                1,
                2,
                Map.of()
        ));
        return requests;
    }

    private static WorkerDescriptor worker(String workerId, String region) {
        return new WorkerDescriptor(
                workerId,
                "group-1",
                "adapter-1",
                Map.of("region", region),
                Map.of()
        );
    }
}
