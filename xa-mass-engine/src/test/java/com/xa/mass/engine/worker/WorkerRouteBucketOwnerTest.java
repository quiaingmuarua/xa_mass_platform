package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class WorkerRouteBucketOwnerTest {

    @Test
    void defaultPolicyIndexesWorkersIntoBoundedGroupRouteBucket() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                        .build()
        ), List.of(
                worker("worker-1", "crawler"),
                worker("worker-2", "crawler"),
                worker("worker-3", "crawler"),
                worker("worker-without-group", null),
                worker("worker-missing-group", "missing")
        ));

        WorkerRouteBucketOwner owner = WorkerRouteBucketOwner.fromSnapshot(snapshot);

        assertEquals(List.of("worker-1", "worker-2"),
                owner.acquire("crawler", WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY, 2));
        assertEquals(Set.of(WorkerRoutingPolicy.DEFAULT_ROUTE_BUCKET_KEY),
                owner.routeBucketKeysByWorkerId("worker-1"));
        assertEquals(Set.of(), owner.routeBucketKeysByWorkerId("worker-without-group"));
        assertEquals(Set.of(), owner.routeBucketKeysByWorkerId("worker-missing-group"));
    }

    @Test
    void customPolicyCanMapWorkerIntoMultipleBucketsWithoutChangingGroupCapability() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                WorkerGroupRecord.builder("crawler")
                        .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                        .build()
        ), List.of(worker("worker-1", "crawler")));
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

    private static Worker worker(String workerId, String groupId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(groupId);
        return worker;
    }
}
