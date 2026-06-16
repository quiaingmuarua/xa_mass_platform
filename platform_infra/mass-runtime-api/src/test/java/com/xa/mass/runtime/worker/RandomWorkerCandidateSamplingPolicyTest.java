package com.xa.mass.runtime.worker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RandomWorkerCandidateSamplingPolicyTest {

    @Test
    void returnsWholeBucketWhenLimitCoversBucket() {
        RandomWorkerCandidateSamplingPolicy policy = new RandomWorkerCandidateSamplingPolicy(bound -> 0);

        assertEquals(List.of("worker-1", "worker-2"),
                policy.sample(context(), List.of("worker-1", "worker-2"), 10));
    }

    @Test
    void samplesBoundedSubsetWithoutFixedPrefixRequirement() {
        RandomWorkerCandidateSamplingPolicy policy = new RandomWorkerCandidateSamplingPolicy(bound -> bound - 1);

        List<String> sample = policy.sample(
                context(),
                List.of(
                        "worker-0",
                        "worker-1",
                        "worker-2",
                        "worker-3",
                        "worker-4",
                        "worker-5",
                        "worker-6",
                        "worker-7",
                        "worker-8",
                        "worker-9"
                ),
                3
        );

        assertEquals(List.of("worker-7", "worker-8", "worker-9"), sample);
    }

    private WorkerCandidateSamplingContext context() {
        return new WorkerCandidateSamplingContext("group-a", "default");
    }
}
