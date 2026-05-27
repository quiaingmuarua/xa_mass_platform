package com.xa.mass.engine.worker;

import com.xa.mass.runtime.worker.WorkerStateProjection;
import com.xa.mass.runtime.worker.WorkerStateProjectionResult;
import com.xa.mass.runtime.worker.WorkerStateProjectionStatus;
import com.xa.mass.runtime.worker.WorkerStateReport;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WorkerStateProjectionOwnerTest {

    @Test
    void appliesStateReportsIntoBoundedProjection() {
        WorkerStateProjectionOwner owner = new WorkerStateProjectionOwner(
                Clock.fixed(Instant.parse("2026-05-18T00:00:00Z"), ZoneOffset.UTC),
                2
        );

        WorkerStateProjectionResult first = owner.applyReport(report(1, "WARMING"));
        WorkerStateProjectionResult second = owner.applyReport(report(2, "READY"));
        WorkerStateProjectionResult third = owner.applyReport(report(3, "DRAINING"));

        assertEquals(WorkerStateProjectionStatus.ACCEPTED, first.status());
        assertEquals(WorkerStateProjectionStatus.ACCEPTED, second.status());
        assertEquals(WorkerStateProjectionStatus.ACCEPTED, third.status());
        WorkerStateProjection projection = owner.projection("worker-1").orElseThrow();
        assertEquals("DRAINING", projection.state());
        assertEquals(3, projection.stateVersion());
        assertEquals(2, projection.recentReports().size());
        assertEquals("DRAINING", projection.recentReports().get(0).state());
        assertEquals("READY", projection.recentReports().get(1).state());
    }

    @Test
    void rejectsStaleAndConflictingReportsWithoutChangingProjection() {
        WorkerStateProjectionOwner owner = new WorkerStateProjectionOwner();
        WorkerStateReport accepted = report(7, "READY");

        assertEquals(WorkerStateProjectionStatus.ACCEPTED, owner.applyReport(accepted).status());
        assertEquals(WorkerStateProjectionStatus.IDEMPOTENT, owner.applyReport(accepted).status());

        WorkerStateProjectionResult stale = owner.applyReport(report(6, "DRAINING"));
        assertEquals(WorkerStateProjectionStatus.STALE, stale.status());
        assertTrue(!stale.projectionChanged());

        WorkerStateProjectionResult conflict = owner.applyReport(
                WorkerStateReport.builder("worker-1", 7, "DRAINING")
                        .reason("different payload")
                        .observedAt(Instant.ofEpochMilli(7_000L))
                        .attributes(Map.of("temperature", "high"))
                        .build()
        );
        assertEquals(WorkerStateProjectionStatus.CONFLICT, conflict.status());
        assertTrue(!conflict.projectionChanged());
        assertEquals("READY", owner.projection("worker-1").orElseThrow().state());
    }

    private static WorkerStateReport report(long version, String state) {
        return WorkerStateReport.builder("worker-1", version, state)
                .reason("test")
                .observedAt(Instant.ofEpochMilli(version * 1_000L))
                .attributes(Map.of("temperature", "normal"))
                .build();
    }
}
