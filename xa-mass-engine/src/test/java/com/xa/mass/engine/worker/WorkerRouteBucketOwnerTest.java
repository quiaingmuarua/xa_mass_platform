package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.Worker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerRouteBucketOwnerTest {

    @Test
    void defaultPolicyIndexesWorkersIntoBoundedGroupRouteBucket() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                        .build()
        ), List.of(
                worker("worker-1", "crawler", Map.of()),
                worker("worker-2", "crawler", Map.of()),
                worker("worker-3", "crawler", Map.of()),
                worker("worker-without-group", null, Map.of()),
                worker("worker-missing-group", "missing", Map.of())
        ));

        WorkerRouteBucketOwner owner = WorkerRouteBucketOwner.fromSnapshot(snapshot);

        List<String> acquired = owner.acquire("crawler", WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY, 2);
        assertEquals(2, acquired.size());
        assertTrue(Set.of("worker-1", "worker-2", "worker-3").containsAll(acquired));
        assertEquals(Set.of(WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY),
                owner.routeBucketKeysByWorkerId("worker-1"));
        assertEquals(Set.of(), owner.routeBucketKeysByWorkerId("worker-without-group"));
        assertEquals(Set.of(), owner.routeBucketKeysByWorkerId("worker-missing-group"));
    }

    @Test
    void largeGroupAcquisitionRemainsBoundedByRequestedLimit() {
        List<Worker> workers = new ArrayList<>();
        for (int i = 0; i < 1_000; i++) {
            workers.add(worker("worker-" + i, "crawler", Map.of()));
        }
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                        .build()
        ), workers);

        WorkerRouteBucketOwner owner = WorkerRouteBucketOwner.fromSnapshot(
                snapshot,
                WorkerRoutingPolicy.defaultPolicy(),
                new RandomWorkerRouteBucketSelectionPolicy(bound -> bound - 1)
        );

        List<String> acquired = owner.acquire("crawler", WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY, 17);
        assertEquals(17, acquired.size());
        assertNotEquals("worker-0", acquired.getFirst());
        assertEquals("worker-983", acquired.getFirst());
        assertEquals("worker-999", acquired.getLast());
    }

    @Test
    void nodeScopedAcquisitionDoesNotFallbackToFullGroupBucket() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                        .build()
        ), List.of(
                worker("worker-node-a-1", "crawler", "node-a", Map.of()),
                worker("worker-node-b", "crawler", "node-b", Map.of()),
                worker("worker-node-a-2", "crawler", "node-a", Map.of())
        ));

        WorkerRouteBucketOwner owner = WorkerRouteBucketOwner.fromSnapshot(snapshot);

        assertEquals(List.of("worker-node-a-1", "worker-node-a-2"),
                owner.acquireForTask("crawler", "node-a", new Task(), 10));
        assertEquals(List.of("worker-node-b"),
                owner.acquireForTask("crawler", "node-b", new Task(), 10));
        assertEquals(List.of(), owner.acquireForTask("crawler", "missing-node", new Task(), 10));
    }

    @Test
    void defaultPolicyUsesOnlyApprovedRouteAttributes() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                        .build()
        ), List.of(
                worker("worker-us", "crawler", Map.of("region", "us", "color", "blue")),
                worker("worker-eu", "crawler", Map.of("region", "eu", "color", "blue"))
        ));
        Task task = new Task();
        task.setSharedConfig(Map.of(TaskSharedConfig.ROUTE_ATTRIBUTES, Map.of(
                "region", "us",
                "color", "blue"
        )));

        WorkerRouteBucketOwner owner = WorkerRouteBucketOwner.fromSnapshot(snapshot);

        assertEquals(List.of("worker-us"), owner.acquireForTask("crawler", task, 10));
        assertEquals(List.of("worker-us", "worker-eu"),
                owner.acquire("crawler", WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY, 10));
    }

    @Test
    void customPolicyCanMapWorkerIntoMultipleBucketsWithoutChangingGroupCapability() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                        .build()
        ), List.of(worker("worker-1", "crawler", Map.of())));
        WorkerRoutingPolicy policy = new WorkerRoutingPolicy() {
            @Override
            public Set<String> routeBucketKeysForTask(Task task) {
                return Set.of("tenant-a");
            }

            @Override
            public Set<String> routeBucketKeysForWorker(Worker worker) {
                return Set.of("tenant-a", "tenant-b");
            }
        };

        WorkerRouteBucketOwner owner = WorkerRouteBucketOwner.fromSnapshot(snapshot, policy);

        assertEquals(List.of("worker-1"), owner.acquireForTask("crawler", new Task(), 10));
        assertEquals(List.of("worker-1"), owner.acquire("crawler", "tenant-b", 10));
        assertEquals(Set.of("tenant-a", "tenant-b"), owner.routeBucketKeysByWorkerId("worker-1"));
    }

    private static Worker worker(String workerId, String groupId, Map<String, String> attributes) {
        return worker(workerId, groupId, null, attributes);
    }

    private static Worker worker(String workerId, String groupId, String adapterNodeId, Map<String, String> attributes) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(groupId);
        worker.setAdapterNodeId(adapterNodeId);
        worker.setAttributes(attributes);
        return worker;
    }
}
