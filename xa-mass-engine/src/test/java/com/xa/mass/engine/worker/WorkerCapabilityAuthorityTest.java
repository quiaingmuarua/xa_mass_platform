package com.xa.mass.engine.worker;

import com.xa.mass.base.model.Worker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCapabilityAuthorityTest {

    private final WorkerCapabilityAuthority authority = new WorkerCapabilityAuthority();

    @Test
    void composesDeclaredWorkerGroupsIntoWorkerRegistrySnapshot() {
        Worker worker = worker("worker-crawler", "crawler");
        worker.setAdapterId("adapter-a");
        worker.setMaxConcurrentWork(3);
        worker.setAttributes(Map.of("country", "us"));
        WorkerGroupRecord declaredGroup = WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .defaultAttributes(Map.of("source", "declared"))
                .defaultMaxConcurrentWork(3)
                .build();

        WorkerRegistrySnapshot snapshot = authority.composeSnapshot(List.of(worker), List.of(declaredGroup));

        WorkerGroupRecord group = snapshot.group("crawler").orElseThrow();
        assertEquals(3, group.defaultMaxConcurrentWork());
        assertEquals(Map.of("source", "declared"), group.defaultAttributes());
        assertTrue(group.projectCodes().contains("demoApp"));
        assertTrue(group.eventBindings().contains(EventBinding.of("crawler.fetch", List.of("demoApp"))));
        assertEquals(List.of("worker-crawler"), List.copyOf(snapshot.workerIdsByGroupId("crawler")));
        assertEquals(List.of("crawler"), List.copyOf(snapshot.groupIdsByEventKey(
                new EventKey("demoApp", "crawler.fetch"))));
    }

    @Test
    void workerLevelCompatibilityFieldsDoNotCreateGroupCapabilityTruth() {
        Worker worker = worker("worker-crawler", "crawler");
        worker.setSupportedProjects(List.of("legacyApp"));
        worker.setSupportedEventCodes(List.of("legacy.fetch"));

        WorkerRegistrySnapshot snapshot = authority.composeSnapshot(List.of(worker));

        assertTrue(snapshot.groups().isEmpty());
        assertEquals(List.of("worker-crawler"), List.copyOf(snapshot.workerIdsByGroupId("crawler")));
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("legacyApp", "legacy.fetch")).isEmpty());
    }

    @Test
    void keepsWorkersWithoutGroupAsRowsButNotGroupIndexCandidates() {
        Worker worker = worker("worker-stateless", null);
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));

        WorkerRegistrySnapshot snapshot = authority.composeSnapshot(List.of(worker));

        assertTrue(snapshot.worker("worker-stateless").isPresent());
        assertTrue(snapshot.groups().isEmpty());
        assertTrue(snapshot.groupIdByWorkerId("worker-stateless").isEmpty());
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")).isEmpty());
    }

    @Test
    void composedSnapshotsArePointInTimeReadViews() {
        Worker worker = worker("worker-crawler", "crawler");
        WorkerGroupRecord crawler = WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build();
        WorkerGroupRecord export = WorkerGroupRecord.builder("export")
                .eventBindings(List.of(EventBinding.of("report.export", List.of("testApp"))))
                .build();

        WorkerRegistrySnapshot first = authority.composeSnapshot(List.of(worker), List.of(crawler));

        worker.setWorkerGroupId("export");
        WorkerRegistrySnapshot second = authority.composeSnapshot(List.of(worker), List.of(export));

        assertTrue(first.group("crawler").isPresent());
        assertTrue(first.group("export").isEmpty());
        assertEquals(List.of("worker-crawler"), List.copyOf(first.workerIdsByGroupId("crawler")));
        assertTrue(second.group("export").isPresent());
        assertTrue(second.group("crawler").isEmpty());
        assertEquals(List.of("worker-crawler"), List.copyOf(second.workerIdsByGroupId("export")));
    }

    @Test
    void reportReplacesOnlyReportOwnedAvailabilityWithinRegistrationCeiling() {
        Worker worker = worker("worker-crawler", "crawler");
        worker.setAdapterNodeId("node-a");
        worker.setAttributes(Map.of("region", "us"));
        WorkerGroupRecord declaredGroup = WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build();

        WorkerCapabilityReportResult result = authority.applyReport(
                WorkerCapabilityReport.builder("worker-crawler", 1)
                        .availableEventCodes(List.of("crawler.parse", "admin.not-approved"))
                        .schedulingAttributes(Map.of("loadClass", "warm"))
                        .agentVersion("agent-2")
                        .build(),
                List.of(worker),
                List.of(declaredGroup)
        );

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED, result.status());
        WorkerRegistrySnapshot snapshot = result.snapshot();
        assertEquals(List.of("crawler"), List.copyOf(snapshot.groupIdsByEventKey(
                new EventKey("demoApp", "crawler.fetch"))));
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "crawler.parse")).isEmpty());
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "admin.not-approved")).isEmpty());
        Worker effectiveWorker = snapshot.worker("worker-crawler").orElseThrow();
        assertEquals(Map.of("region", "us", "loadClass", "warm"), effectiveWorker.getAttributes());
        assertEquals("agent-2", effectiveWorker.getAgentVersion());
        assertEquals("node-a", effectiveWorker.getAdapterNodeId());
        assertEquals(List.of("worker-crawler"), List.copyOf(snapshot.workerIdsByAdapterNodeId("node-a")));
        assertEquals(List.of("worker-crawler"),
                List.copyOf(snapshot.workerIdsByAdapterNodeGroup("node-a", "crawler")));
    }

    @Test
    void reportOrderingRejectsStaleConflictAndUnknownWorkerWithoutPublishingSnapshot() {
        Worker worker = worker("worker-crawler", "crawler");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch", "crawler.parse"));
        WorkerCapabilityReport accepted = WorkerCapabilityReport.builder("worker-crawler", 7)
                .availableEventCodes(List.of("crawler.fetch"))
                .build();

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED,
                authority.applyReport(accepted, List.of(worker)).status());

        WorkerCapabilityReportResult idempotent = authority.applyReport(accepted, List.of(worker));
        assertEquals(WorkerCapabilityReportStatus.IDEMPOTENT, idempotent.status());
        assertTrue(!idempotent.snapshotChanged());

        WorkerCapabilityReportResult stale = authority.applyReport(
                WorkerCapabilityReport.builder("worker-crawler", 6)
                        .availableEventCodes(List.of("crawler.parse"))
                        .build(),
                List.of(worker)
        );
        assertEquals(WorkerCapabilityReportStatus.STALE, stale.status());
        assertTrue(!stale.snapshotChanged());

        WorkerCapabilityReportResult conflict = authority.applyReport(
                WorkerCapabilityReport.builder("worker-crawler", 7)
                        .availableEventCodes(List.of("crawler.parse"))
                        .build(),
                List.of(worker)
        );
        assertEquals(WorkerCapabilityReportStatus.CONFLICT, conflict.status());
        assertTrue(!conflict.snapshotChanged());

        WorkerCapabilityReportResult unknown = authority.applyReport(
                WorkerCapabilityReport.builder("missing-worker", 1)
                        .availableEventCodes(List.of("crawler.fetch"))
                        .build(),
                List.of(worker)
        );
        assertEquals(WorkerCapabilityReportStatus.UNKNOWN_WORKER, unknown.status());
        assertTrue(!unknown.snapshotChanged());
    }

    private static Worker worker(String workerId, String workerGroupId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId(workerGroupId);
        return worker;
    }
}
