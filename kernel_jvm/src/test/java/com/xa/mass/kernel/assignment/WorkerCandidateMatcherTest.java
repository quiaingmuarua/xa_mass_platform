package com.xa.mass.kernel.assignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerCandidateMatcherTest {

    @Test
    void matchesBoundedDescriptorsByPriorityAndUsesEachWorkerOnce() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(catalog.getWorkerDescriptors("group-1", List.of("w1", "w2")))
                .thenReturn(Map.of(
                        "w1",
                        descriptor("w1", "cn", 80),
                        "w2",
                        descriptor("w2", "us", 50)
                ));
        WorkerCandidateMatcher matcher = new WorkerCandidateMatcher(catalog);
        LinkedHashMap<String, WorkerCandidateRequest> requests =
                new LinkedHashMap<>();
        requests.put("later", new WorkerCandidateRequest(
                20,
                2,
                Map.of("worker.region", Map.of("$in", List.of("cn", "us")))
        ));
        requests.put("first", new WorkerCandidateRequest(
                10,
                1,
                Map.of("worker.battery", Map.of("$gte", 60))
        ));

        var result = matcher.matchExplicitWorkerCandidates(
                "group-1",
                Map.of("w1", 101L, "w2", 102L),
                Map.of(
                        "first", List.of("w1", "w2"),
                        "later", List.of("w1", "w2")
                ),
                requests
        );

        assertEquals(List.of("later", "first"), List.copyOf(result.keySet()));
        assertEquals(
                List.of("w1"),
                result.get("first").stream().map(
                        CandidateWorkerCache.CandidateWorkerEntry::workerId
                ).toList()
        );
        assertEquals(
                List.of("w2"),
                result.get("later").stream().map(
                        CandidateWorkerCache.CandidateWorkerEntry::workerId
                ).toList()
        );
    }

    @Test
    void rejectsIndexRulesWithoutExpandingTheBoundedRead() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        WorkerCandidateMatcher matcher = new WorkerCandidateMatcher(catalog);

        var result = matcher.filterCandidateWorkerIds(
                "group-1",
                Map.of("candidate", List.of("w1")),
                Map.of("candidate", new WorkerCandidateRequest(
                        1,
                        1,
                        Map.of("index.worker.region", Map.of("$eq", "cn"))
                ))
        );

        assertTrue(result.get("candidate").isEmpty());
    }

    private static WorkerDescriptor descriptor(
            String workerId,
            String region,
            int battery
    ) {
        return new WorkerDescriptor(
                workerId,
                "group-1",
                "adapter-1",
                Map.of("region", region, "battery", battery),
                Map.of("pool", "default")
        );
    }
}
