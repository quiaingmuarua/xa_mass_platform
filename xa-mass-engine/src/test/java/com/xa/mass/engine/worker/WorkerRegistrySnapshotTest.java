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
        WorkerGroupRecord crawler = group("crawler", "node-a",
                EventBinding.of("crawler.fetch", List.of("demoApp", "searchApp")));
        WorkerGroupRecord export = group("export", "node-b",
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
    void indexesOneAdapterNodeHostingMultipleGroups() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                group("crawler", "adapter-node-1", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("export", "adapter-node-1", EventBinding.of("report.export", List.of("reportApp"))),
                group("other", "adapter-node-2", EventBinding.of("other.event", List.of("otherApp")))
        ), List.of());

        assertEquals(Set.of("crawler", "export"),
                snapshot.groupIdsByAdapterNodeId("adapter-node-1"));
        assertEquals(Set.of("other"),
                snapshot.groupIdsByAdapterNodeId("adapter-node-2"));
    }

    @Test
    void workerRegisterUpdateAndDeleteRebuildsWorkerIndexes() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                group("crawler", "node-a", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("export", "node-b", EventBinding.of("report.export", List.of("reportApp")))
        ), List.of(worker("worker-1", "crawler")));

        assertEquals(Set.of("worker-1"), snapshot.workerIdsByGroupId("crawler"));
        assertEquals("crawler", snapshot.groupIdByWorkerId("worker-1").orElseThrow());

        WorkerRegistrySnapshot moved = snapshot.withWorker(worker("worker-1", "export"));

        assertTrue(moved.workerIdsByGroupId("crawler").isEmpty());
        assertEquals(Set.of("worker-1"), moved.workerIdsByGroupId("export"));
        assertEquals("export", moved.groupIdByWorkerId("worker-1").orElseThrow());

        WorkerRegistrySnapshot deleted = moved.withoutWorker("worker-1");

        assertTrue(deleted.workerIdsByGroupId("export").isEmpty());
        assertTrue(deleted.groupIdByWorkerId("worker-1").isEmpty());
    }

    @Test
    void groupUpdateAndDeleteRebuildsEventAndAdapterIndexes() {
        WorkerGroupRecord initial = group("crawler", "node-a",
                EventBinding.of("crawler.fetch", List.of("demoApp")));
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(initial), List.of());

        WorkerGroupRecord updated = WorkerGroupRecord.builder("crawler")
                .adapterNodeId("node-b")
                .eventBindings(List.of(EventBinding.of("crawler.parse", List.of("parserApp"))))
                .defaultAttributes(Map.of("region", "us"))
                .defaultMaxConcurrentWork(3)
                .build();
        WorkerRegistrySnapshot reRegistered = snapshot.withGroup(updated);

        assertTrue(reRegistered.groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")).isEmpty());
        assertTrue(reRegistered.groupIdsByAdapterNodeId("node-a").isEmpty());
        assertEquals(Set.of("crawler"),
                reRegistered.groupIdsByEventKey(new EventKey("parserApp", "crawler.parse")));
        assertEquals(Set.of("crawler"),
                reRegistered.groupIdsByAdapterNodeId("node-b"));
        assertEquals(3, reRegistered.group("crawler").orElseThrow().defaultMaxConcurrentWork());

        WorkerRegistrySnapshot deleted = reRegistered.withoutGroup("crawler");

        assertTrue(deleted.groupIdsByEventKey(new EventKey("parserApp", "crawler.parse")).isEmpty());
        assertTrue(deleted.groupIdsByAdapterNodeId("node-b").isEmpty());
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

    private static WorkerGroupRecord group(String groupId, String adapterNodeId, EventBinding binding) {
        return WorkerGroupRecord.builder(groupId)
                .adapterNodeId(adapterNodeId)
                .eventBindings(List.of(binding))
                .build();
    }

    private static Worker worker(String workerId, String groupId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(groupId);
        return worker;
    }
}
