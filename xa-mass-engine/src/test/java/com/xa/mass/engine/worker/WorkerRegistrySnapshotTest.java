package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.EventBinding;
import com.xa.mass.runtime.worker.WorkerGroupRecord;

import com.xa.mass.base.model.Worker;
import com.xa.mass.runtime.worker.EventKey;
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
    void workerRegisterUpdateAndDeleteMaintainsDiagnosticRowsOnly() {
        WorkerRegistrySnapshot snapshot = WorkerRegistrySnapshot.from(List.of(
                group("crawler", EventBinding.of("crawler.fetch", List.of("demoApp"))),
                group("export", EventBinding.of("report.export", List.of("reportApp")))
        ), List.of(worker("worker-1", "node-a", "crawler")));

        assertEquals("crawler", snapshot.worker("worker-1").orElseThrow().getWorkerGroupId());

        WorkerRegistrySnapshot moved = snapshot.withWorker(worker("worker-1", "node-b", "export"));

        assertEquals("export", moved.worker("worker-1").orElseThrow().getWorkerGroupId());
        assertEquals("node-b", moved.worker("worker-1").orElseThrow().getAdapterNodeId());

        WorkerRegistrySnapshot deleted = moved.withoutWorker("worker-1");

        assertTrue(deleted.worker("worker-1").isEmpty());
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
