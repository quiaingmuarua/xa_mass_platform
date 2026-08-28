package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.xa.mass.kernel.pacer.dispatch.WorkerCandidateMechanism.WorkerCandidateObservation;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerCandidateMatcherTest {

    @Test
    void matchesCanonicalPropertiesAndKeepsWorkersUniqueByPriority() {
        WorkerCandidateObservation east = worker("worker-east", "east");
        WorkerCandidateObservation west = worker("worker-west", "west");
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

        Map<String, List<WorkerCandidateObservation>> matched =
                new WorkerCandidateMatcher().match(
                        "group-1",
                        Map.of(
                                "preferred", List.of(east, west),
                                "fallback", List.of(east, west)
                        ),
                        requests,
                        true,
                        true
                );

        assertEquals(List.of(east), matched.get("preferred"));
        assertEquals(List.of(west), matched.get("fallback"));
    }

    private static WorkerCandidateObservation worker(
            String workerId,
            String region
    ) {
        return new WorkerCandidateObservation(
                workerId,
                "group-1",
                new WorkerDescriptor(
                        workerId,
                        "group-1",
                        "adapter-1",
                        Map.of("region", region),
                        Map.of()
                ),
                mock(WorkerCandidateReference.class)
        );
    }
}
