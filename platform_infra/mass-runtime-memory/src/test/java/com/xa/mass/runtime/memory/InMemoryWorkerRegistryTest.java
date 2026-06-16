package com.xa.mass.runtime.memory;

import com.xa.mass.runtime.contract.WorkerRegistryContractTest;
import com.xa.mass.runtime.worker.WorkerCandidateSamplingPolicy;
import com.xa.mass.runtime.worker.WorkerMeta;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.runtime.worker.WorkerCandidateBucketPolicy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryWorkerRegistryTest extends WorkerRegistryContractTest {

    @Override
    protected WorkerRegistry createRegistry(WorkerCandidateSamplingPolicy samplingPolicy) {
        return new InMemoryWorkerRegistry(samplingPolicy);
    }

    @Test
    void concurrentReserveDoesNotExceedCapacity() throws InterruptedException {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 4, Set.of(eventKey()));

        int contenders = 16;
        CountDownLatch ready = new CountDownLatch(contenders);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int index = 0; index < contenders; index++) {
            int taskIndex = index;
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (registry.tryReserve("group-a", "worker-1", "task-" + taskIndex, 1, 1000).accepted()) {
                    accepted.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(4, accepted.get());
        assertEquals(4, registry.slot("group-a", "worker-1").orElseThrow().reservedCount());
    }

    @Test
    void routeAttributeUpdateRemovesOnlyKnownPreviousBucketMembership() {
        WorkerRegistry registry = new InMemoryWorkerRegistry(
                regionRoutePolicy(),
                (context, workerIds, maxCandidateCount) -> workerIds.stream().limit(maxCandidateCount).toList()
        );
        registry.upsertSlot(workerMeta("worker-1", "group-a", "us"), 1, Set.of(eventKey()));
        registry.upsertSlot(workerMeta("worker-2", "group-a", "us"), 1, Set.of(eventKey()));

        assertEquals(List.of("worker-1", "worker-2"),
                registry.acquireCandidates("group-a", "attr:region=us", 10));

        registry.upsertSlot(workerMeta("worker-1", "group-a", "eu"), 1, Set.of(eventKey()));

        assertEquals(List.of("worker-2"),
                registry.acquireCandidates("group-a", "attr:region=us", 10));
        assertEquals(List.of("worker-1"),
                registry.acquireCandidates("group-a", "attr:region=eu", 10));
    }

    @Test
    void cleanupRemovedSlotWaitsForOccupancyToDrain() {
        WorkerRegistry registry = createRegistry();
        registry.upsertSlot(meta("worker-1", "group-a"), 1, Set.of(eventKey()));
        assertTrue(registry.tryReserve("group-a", "worker-1", "task-1", 1, 1000).accepted());
        assertTrue(registry.markSlotRemoving("group-a", "worker-1", "test"));

        assertEquals(0, registry.cleanupRemovedSlots("group-a", 10).removed());
        registry.releaseReservation("group-a", "worker-1", "task-1", 1);
        assertEquals(1, registry.cleanupRemovedSlots("group-a", 10).removed());
        assertTrue(registry.slot("group-a", "worker-1").isEmpty());
    }

    private WorkerMeta workerMeta(String workerId, String groupId, String region) {
        return new WorkerMeta(
                workerId,
                groupId,
                "polling",
                Map.of("region", region),
                "agent-1",
                "runtime-1",
                1_000,
                "AVAILABLE"
        );
    }

    private static WorkerCandidateBucketPolicy regionRoutePolicy() {
        return new WorkerCandidateBucketPolicy() {
            @Override
            public Set<String> candidateBucketKeysForWorkerMeta(WorkerMeta meta) {
                String region = meta == null || meta.attributes() == null ? null : meta.attributes().get("region");
                if (region == null || region.isBlank()) {
                    return Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY);
                }
                return Set.of(WorkerCandidateBucketPolicy.DEFAULT_CANDIDATE_BUCKET_KEY, "attr:region=" + region.trim());
            }

            @Override
            public int maxBucketFanout() {
                return 2;
            }
        };
    }
}
