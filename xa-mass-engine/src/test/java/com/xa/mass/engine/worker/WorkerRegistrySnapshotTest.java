package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerRegistrySnapshotTest {

    @Test
    void indexesGroupsByConcreteEventKey() {
        WorkerGroupRecord crawler = group("crawler",
                EventBinding.of("crawler.fetch", List.of("demoApp", "searchApp")));
        WorkerGroupRecord export = group("export",
                EventBinding.of("report.export", List.of("reportApp")));

        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(crawler, export), List.of(
                worker("worker-1", "crawler"),
                worker("worker-2", "export")
        ));

        assertEquals(Set.of("crawler"),
                snapshot.groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")));
        assertEquals(Set.of("crawler"),
                snapshot.groupIdsByEventKey(new EventKey("searchApp", "crawler.fetch")));
        assertEquals(Set.of("export"),
                snapshot.groupIdsByEventKey(new EventKey("reportApp", "report.export")));
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "report.export")).isEmpty());
    }

    @Test
    void workerRegisterUpdateAndDeleteRebuildsWorkerIndexes() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                group("crawler", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("export", EventBinding.of("report.export", List.of("reportApp")))
        ), List.of(worker("worker-1", "node-a", "crawler")));

        assertEquals(Set.of("worker-1"), snapshot.workerIdsByGroupId("crawler"));
        assertEquals(Set.of("worker-1"), snapshot.workerIdsByAdapterNodeId("node-a"));
        assertEquals(Set.of("worker-1"), snapshot.workerIdsByAdapterNodeGroup("node-a", "crawler"));
        assertEquals("crawler", snapshot.groupIdByWorkerId("worker-1").orElseThrow());

        WorkerRegistrySnapshot moved = snapshot.withWorker(worker("worker-1", "node-b", "export"));

        assertTrue(moved.workerIdsByGroupId("crawler").isEmpty());
        assertTrue(moved.workerIdsByAdapterNodeGroup("node-a", "crawler").isEmpty());
        assertEquals(Set.of("worker-1"), moved.workerIdsByGroupId("export"));
        assertEquals(Set.of("worker-1"), moved.workerIdsByAdapterNodeId("node-b"));
        assertEquals(Set.of("worker-1"), moved.workerIdsByAdapterNodeGroup("node-b", "export"));
        assertEquals("export", moved.groupIdByWorkerId("worker-1").orElseThrow());

        WorkerRegistrySnapshot deleted = moved.withoutWorker("worker-1");

        assertTrue(deleted.workerIdsByGroupId("export").isEmpty());
        assertTrue(deleted.workerIdsByAdapterNodeId("node-b").isEmpty());
        assertTrue(deleted.workerIdsByAdapterNodeGroup("node-b", "export").isEmpty());
        assertTrue(deleted.groupIdByWorkerId("worker-1").isEmpty());
    }

    @Test
    void groupUpdateAndDeleteRebuildsEventIndexes() {
        WorkerGroupRecord initial = group("crawler",
                EventBinding.of("crawler.fetch", List.of("demoApp")));
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(initial), List.of());

        WorkerGroupRecord updated = WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.parse", List.of("parserApp"))))
                .defaultAttributes(Map.of("region", "us"))
                .defaultMaxConcurrentWork(3)
                .build();
        WorkerRegistrySnapshot reRegistered = snapshot.withGroup(updated);

        assertTrue(reRegistered.groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")).isEmpty());
        assertEquals(Set.of("crawler"),
                reRegistered.groupIdsByEventKey(new EventKey("parserApp", "crawler.parse")));
        assertEquals(3, reRegistered.group("crawler").orElseThrow().defaultMaxConcurrentWork());

        WorkerRegistrySnapshot deleted = reRegistered.withoutGroup("crawler");

        assertTrue(deleted.groupIdsByEventKey(new EventKey("parserApp", "crawler.parse")).isEmpty());
        assertTrue(deleted.group("crawler").isEmpty());
    }

    @Test
    void normalizesCapabilityInputsAndRejectsUnindexableBindings() {
        EventBinding binding = EventBinding.of(" crawler.fetch ", List.of(" demoApp ", "demoApp", " searchApp "));

        assertEquals("crawler.fetch", binding.eventCode());
        assertEquals(List.of("demoApp", "searchApp"), binding.projectCodes());
        assertEquals(Set.of(new EventKey("demoApp", "crawler.fetch"), new EventKey("searchApp", "crawler.fetch")),
                binding.eventKeys());

        assertThrows(IllegalArgumentException.class, () -> EventBinding.of("crawler.fetch", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EventKey("demoApp", " "));
    }

    private static WorkerGroupRecord group(String groupId, EventBinding binding) {
        return WorkerGroupRecord.builder(groupId)
                .eventBindings(List.of(binding))
                .build();
    }

    private static Worker worker(String workerId, String groupId) {
        return worker(workerId, null, groupId);
    }

    private static Worker worker(String workerId, String adapterNodeId, String groupId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setAdapterNodeId(adapterNodeId);
        worker.setWorkerGroupId(groupId);
        return worker;
    }
}
