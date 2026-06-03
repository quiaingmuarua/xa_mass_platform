package com.xa.mass.engine.strategy;

import com.xa.mass.engine.runtime.scheduling.ResolvedWorkerSchedulingPolicy;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WorkerTaskSelectorFactoryTest {

    @Test
    void selectorUsesResolvedWorkerSchedulingPolicyFields() {
        ResolvedWorkerSchedulingPolicy policy = new ResolvedWorkerSchedulingPolicy(
                "task-1",
                "demoApp",
                "event.probe",
                List.of("pool-a", "pool-b"),
                "node-a",
                "us",
                Map.of("region", "us"),
                Set.of("route:us"),
                "worker-target",
                Map.of("fingerprintProfile", "fp-alpha")
        );

        WorkerTaskSelector selector = WorkerTaskSelectorFactory.fromPolicy(policy);

        assertEquals("task-1", selector.taskId());
        assertEquals(List.of("pool-a", "pool-b"), selector.workerGroupIds());
        assertEquals("node-a", selector.adapterNodeId());
        assertEquals("worker-target", selector.targetWorkerId());
        assertEquals(Set.of("route:us"), selector.routeBucketKeys());
    }

    @Test
    void selectorChangesWhenResolvedWorkerSchedulingPolicyIsPerturbed() {
        ResolvedWorkerSchedulingPolicy baseline = policy("task-1", List.of("pool-a"), "node-a",
                "worker-a", Set.of("route:a"));
        ResolvedWorkerSchedulingPolicy perturbed = policy("task-1", List.of("pool-b"), "node-b",
                "worker-b", Set.of("route:b"));

        WorkerTaskSelector baselineSelector = WorkerTaskSelectorFactory.fromPolicy(baseline);
        WorkerTaskSelector perturbedSelector = WorkerTaskSelectorFactory.fromPolicy(perturbed);

        assertEquals(List.of("pool-a"), baselineSelector.workerGroupIds());
        assertEquals(List.of("pool-b"), perturbedSelector.workerGroupIds());
        assertEquals("node-a", baselineSelector.adapterNodeId());
        assertEquals("node-b", perturbedSelector.adapterNodeId());
        assertEquals("worker-a", baselineSelector.targetWorkerId());
        assertEquals("worker-b", perturbedSelector.targetWorkerId());
        assertEquals(Set.of("route:a"), baselineSelector.routeBucketKeys());
        assertEquals(Set.of("route:b"), perturbedSelector.routeBucketKeys());
    }

    private ResolvedWorkerSchedulingPolicy policy(String taskId,
                                                  List<String> workerGroupIds,
                                                  String adapterNodeId,
                                                  String targetWorkerId,
                                                  Set<String> routeBucketKeys) {
        return new ResolvedWorkerSchedulingPolicy(
                taskId,
                "demoApp",
                "event.probe",
                workerGroupIds,
                adapterNodeId,
                null,
                Map.of(),
                routeBucketKeys,
                targetWorkerId,
                Map.of()
        );
    }
}
