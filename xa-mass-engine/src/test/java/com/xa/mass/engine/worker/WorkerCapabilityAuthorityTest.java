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
    void composesCurrentCompatibilityFieldsIntoWorkerRegistrySnapshot() {
        Worker worker = worker("worker-crawler", "crawler");
        worker.setAdapterId("adapter-a");
        worker.setMaxConcurrentWork(3);
        worker.setAttributes(Map.of("country", "us"));
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));

        WorkerRegistrySnapshot snapshot = authority.composeSnapshot(List.of(worker));

        WorkerGroupRecord group = snapshot.group("crawler").orElseThrow();
        assertEquals("adapter-a", group.adapterNodeId());
        assertEquals(3, group.defaultMaxConcurrentWork());
        assertEquals(Map.of("country", "us"), group.defaultAttributes());
        assertTrue(group.projectCodes().contains("demoApp"));
        assertTrue(group.eventBindings().contains(EventBinding.of("crawler.fetch", List.of("demoApp"))));
        assertEquals(List.of("worker-crawler"), List.copyOf(snapshot.workerIdsByGroupId("crawler")));
        assertEquals(List.of("crawler"), List.copyOf(snapshot.groupIdsByEventKey(
                new EventKey("demoApp", "crawler.fetch"))));
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
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch"));

        WorkerRegistrySnapshot first = authority.composeSnapshot(List.of(worker));

        worker.setWorkerGroupId("export");
        worker.setSupportedProjects(List.of("testApp"));
        worker.setSupportedEventCodes(List.of("report.export"));
        WorkerRegistrySnapshot second = authority.composeSnapshot(List.of(worker));

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
        worker.setSupportedProjects(List.of("demoApp"));
        worker.setSupportedEventCodes(List.of("crawler.fetch", "crawler.parse"));
        worker.setAttributes(Map.of("region", "us"));

        WorkerCapabilityReportResult result = authority.applyReport(
                WorkerCapabilityReport.builder("worker-crawler", 1)
                        .availableEventCodes(List.of("crawler.parse", "admin.not-approved"))
                        .schedulingAttributes(Map.of("loadClass", "warm"))
                        .agentVersion("agent-2")
                        .build(),
                List.of(worker)
        );

        assertEquals(WorkerCapabilityReportStatus.ACCEPTED, result.status());
        WorkerRegistrySnapshot snapshot = result.snapshot();
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")).isEmpty());
        assertEquals(List.of("crawler"), List.copyOf(snapshot.groupIdsByEventKey(
                new EventKey("demoApp", "crawler.parse"))));
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "admin.not-approved")).isEmpty());
        assertEquals(Map.of("region", "us", "loadClass", "warm"),
                snapshot.group("crawler").orElseThrow().defaultAttributes());
        assertEquals("agent-2", snapshot.worker("worker-crawler").orElseThrow().getAgentVersion());
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
