package com.xa.mass.kernel.pacer.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkerCandidateMatcherTest {

    @Test
    void sharedPoolUsesOneCanonicalLoadAndAllowsOverlappingMatches() {
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
        WorkerCandidateMatcher matcher = matcher(catalog);
        WorkerCandidateMatcher.MatchPlan plan = matcher.prepare(
                "group-1",
                rules()
        );

        Map<String, List<WorkerDescriptor>> matched =
                matcher.matchSharedWorkerPool(
                        "group-1",
                        List.of("worker-east", "worker-west"),
                        plan
                );

        assertEquals(List.of(east), matched.get("preferred"));
        assertEquals(List.of(east, west), matched.get("fallback"));
        verify(catalog).getWorkerDescriptors(
                "group-1",
                List.of("worker-east", "worker-west")
        );
    }

    @Test
    void candidateScopedRangesStayIndependentWithoutSelectionSemantics() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-east", "worker-west", "outside-input")
        )).thenReturn(Map.of(
                "worker-east", worker("worker-east", "east"),
                "worker-west", worker("worker-west", "west")
        ));
        WorkerCandidateMatcher matcher = matcher(catalog);
        WorkerCandidateMatcher.MatchPlan plan = matcher.prepare(
                "group-1",
                rules()
        );

        Map<String, List<WorkerDescriptor>> matched =
                matcher.matchCandidateScopedWorkerIds(
                        "group-1",
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
                        plan
                );

        assertEquals(
                List.of("worker-east"),
                matched.get("preferred").stream()
                        .map(WorkerDescriptor::workerId)
                        .toList()
        );
        assertEquals(
                List.of("worker-east", "worker-west"),
                matched.get("fallback").stream()
                        .map(WorkerDescriptor::workerId)
                        .toList()
        );
        verify(catalog).getWorkerDescriptors(
                "group-1",
                List.of("worker-east", "worker-west", "outside-input")
        );
    }

    @Test
    void invalidRuleAndWrongGroupDescriptorAreIsolatedPerCandidate() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        when(catalog.getWorkerDescriptors(
                "group-1",
                List.of("worker-east", "worker-other")
        )).thenReturn(Map.of(
                "worker-east", worker("worker-east", "east"),
                "worker-other", new WorkerDescriptor(
                        "worker-other",
                        "group-2",
                        "adapter-2",
                        Map.of("region", "east"),
                        Map.of()
                )
        ));
        LinkedHashMap<String, Map<String, Object>> rules =
                new LinkedHashMap<>();
        rules.put("invalid", Map.of(
                "worker.region",
                Map.of("$unknown", "east")
        ));
        rules.put("valid", Map.of());
        WorkerCandidateMatcher matcher = matcher(catalog);
        WorkerCandidateMatcher.MatchPlan plan = matcher.prepare(
                "group-1",
                rules
        );

        Map<String, List<WorkerDescriptor>> matched =
                matcher.matchSharedWorkerPool(
                        "group-1",
                        List.of("worker-east", "worker-other"),
                        plan
                );

        assertEquals(List.of(), matched.get("invalid"));
        assertEquals(List.of("worker-east"), matched.get("valid").stream()
                .map(WorkerDescriptor::workerId)
                .toList());
    }

    @Test
    void derivesOnlyCurrentUnrestrictedAndExplicitWorkerIdSources() {
        LinkedHashMap<String, Map<String, Object>> rules =
                new LinkedHashMap<>();
        rules.put("unrestricted", Map.of());
        rules.put("identity", Map.of(
                "workerId",
                Map.of("$in", List.of("worker-1", "worker-2"))
        ));
        rules.put("ordinary", Map.of(
                "worker.region",
                Map.of("$eq", "east")
        ));
        rules.put("invalid", Map.of(
                "worker.region",
                Map.of("$unknown", "east")
        ));
        WorkerCandidateMatcher matcher = matcher(
                mock(WorkerResourceCatalog.class)
        );
        WorkerCandidateMatcher.MatchPlan plan = matcher.prepare(
                "group-1",
                rules
        );

        assertEquals(
                Set.of("unrestricted"),
                matcher.unrestrictedCandidateIds(plan)
        );
        Map<String, List<String>> explicit =
                matcher.explicitWorkerIdsByCandidate(plan, 100);
        assertEquals(
                List.of("worker-1", "worker-2"),
                explicit.get("identity")
        );
        assertFalse(explicit.containsKey("ordinary"));
        assertFalse(explicit.containsKey("invalid"));
    }

    @Test
    void oneRuleIsPreparedOnceForSourceMatchAndRematch() {
        WorkerResourceCatalog catalog = mock(WorkerResourceCatalog.class);
        ConstraintEvaluator evaluator = spy(new ConstraintEvaluator());
        Map<String, Object> rawRule = Map.of(
                "workerId",
                Map.of("$eq", "worker-1")
        );
        when(catalog.getWorkerDescriptors("group-1", List.of("worker-1")))
                .thenReturn(Map.of(
                        "worker-1",
                        worker("worker-1", "east")
                ));
        WorkerCandidateMatcher matcher = new WorkerCandidateMatcher(
                catalog,
                evaluator
        );

        WorkerCandidateMatcher.MatchPlan plan = matcher.prepare(
                "group-1",
                Map.of("candidate", rawRule)
        );
        matcher.explicitWorkerIdsByCandidate(plan, 100);
        matcher.matchSharedWorkerPool(
                "group-1",
                List.of("worker-1"),
                plan
        );
        matcher.matchCandidateScopedWorkerIds(
                "group-1",
                Map.of("candidate", List.of("worker-1")),
                plan
        );

        verify(evaluator, times(1)).normalize(same(rawRule));
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

    private static WorkerCandidateMatcher matcher(
            WorkerResourceCatalog catalog
    ) {
        return new WorkerCandidateMatcher(catalog);
    }

    private static LinkedHashMap<String, Map<String, Object>> rules() {
        LinkedHashMap<String, Map<String, Object>> rules =
                new LinkedHashMap<>();
        rules.put(
                "preferred",
                Map.of("worker.region", Map.of("$eq", "east"))
        );
        rules.put("fallback", Map.of());
        return rules;
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
