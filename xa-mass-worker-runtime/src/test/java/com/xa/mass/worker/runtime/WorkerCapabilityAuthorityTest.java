package com.xa.mass.worker.runtime;

import com.xa.mass.worker.runtime.resource.EventBinding;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;

import com.xa.mass.runtime.worker.EventKey;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReport;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportResult;
import com.xa.mass.worker.runtime.report.WorkerCapabilityReportStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerCapabilityAuthorityTest {

    private final WorkerCapabilityAuthority authority = new WorkerCapabilityAuthority();

    @Test
    void composesDeclaredWorkerGroupsIntoWorkerRegistrySnapshot() {
        WorkerDeclarationRecord worker = worker("worker-crawler", "crawler", 3, Map.of("country", "us"));
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
        assertTrue(snapshot.worker("worker-crawler").isPresent());
        assertEquals(List.of("crawler"), List.copyOf(snapshot.groupIdsByEventKey(
                new EventKey("demoApp", "crawler.fetch"))));
    }

    @Test
    void workerLevelCompatibilityFieldsDoNotCreateGroupCapabilityTruth() {
        WorkerDeclarationRecord worker = worker("worker-crawler", "crawler");

        WorkerRegistrySnapshot snapshot = authority.composeSnapshot(List.of(worker));

        assertTrue(snapshot.groups().isEmpty());
        assertTrue(snapshot.worker("worker-crawler").isPresent());
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("legacyApp", "legacy.fetch")).isEmpty());
    }

    @Test
    void keepsWorkersWithoutGroupAsRowsButNotGroupIndexCandidates() {
        WorkerDeclarationRecord worker = worker("worker-stateless", null);

        WorkerRegistrySnapshot snapshot = authority.composeSnapshot(List.of(worker));

        assertTrue(snapshot.worker("worker-stateless").isPresent());
        assertTrue(snapshot.groups().isEmpty());
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "crawler.fetch")).isEmpty());
    }

    @Test
    void composedSnapshotsArePointInTimeReadViews() {
        WorkerDeclarationRecord worker = worker("worker-crawler", "crawler");
        WorkerGroupRecord crawler = WorkerGroupRecord.builder("crawler")
                .eventBindings(List.of(EventBinding.of("crawler.fetch", List.of("demoApp"))))
                .build();
        WorkerGroupRecord export = WorkerGroupRecord.builder("export")
                .eventBindings(List.of(EventBinding.of("report.export", List.of("testApp"))))
                .build();

        WorkerRegistrySnapshot first = authority.composeSnapshot(List.of(worker), List.of(crawler));

        WorkerDeclarationRecord movedWorker = worker("worker-crawler", "export");
        WorkerRegistrySnapshot second = authority.composeSnapshot(List.of(movedWorker), List.of(export));

        assertTrue(first.group("crawler").isPresent());
        assertTrue(first.group("export").isEmpty());
        assertEquals("crawler", first.worker("worker-crawler").orElseThrow().workerGroupId());
        assertTrue(second.group("export").isPresent());
        assertTrue(second.group("crawler").isEmpty());
        assertEquals("export", second.worker("worker-crawler").orElseThrow().workerGroupId());
    }

    @Test
    void reportReplacesOnlyReportOwnedAvailabilityWithinRegistrationCeiling() {
        WorkerDeclarationRecord worker = worker("worker-crawler", "crawler", 1, Map.of("region", "us"));
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
        WorkerRegistrySnapshot snapshot = authority.composeSnapshot(List.of(worker), List.of(declaredGroup));
        assertEquals(List.of("crawler"), List.copyOf(snapshot.groupIdsByEventKey(
                new EventKey("demoApp", "crawler.fetch"))));
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "crawler.parse")).isEmpty());
        assertTrue(snapshot.groupIdsByEventKey(new EventKey("demoApp", "admin.not-approved")).isEmpty());
        WorkerDeclarationRecord effectiveWorker = snapshot.worker("worker-crawler").orElseThrow();
        assertEquals(Map.of("region", "us", "loadClass", "warm"), effectiveWorker.attributes());
        assertEquals("agent-2", effectiveWorker.agentVersion());
    }

    @Test
    void reportOrderingRejectsStaleConflictAndUnknownWorkerWithoutPublishingSnapshot() {
        WorkerDeclarationRecord worker = worker("worker-crawler", "crawler");
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

    private static WorkerDeclarationRecord worker(String workerId, String workerGroupId) {
        return worker(workerId, workerGroupId, 1, Map.of());
    }

    private static WorkerDeclarationRecord worker(String workerId,
                                                  String workerGroupId,
                                                  int maxConcurrentWork,
                                                  Map<String, String> attributes) {
        return new WorkerDeclarationRecord(workerId, workerGroupId, null, null, maxConcurrentWork, attributes);
    }
}
