package com.xa.mass.worker.runtime.selection;

import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandKind;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandTransitionCommand;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.GROUP_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.TASK_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.groupIsReadable;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.load;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.request;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.row;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.scoreBandRuntime;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.selectable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerSelectionAtomicRuntimeTest {

    @Test
    void lockConflictSkipsClaimAndContinuesToNextSelectedWorker() {
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(
                schedulingViewRuntime,
                admissionRuntime,
                scoreBandRuntime(
                        row("worker-primary", Map.of("region", "us", "routingTags", "us")),
                        row("worker-secondary", Map.of("region", "us", "routingTags", "us"))));
        groupIsReadable(schedulingViewRuntime);
        selectable(schedulingViewRuntime, "worker-primary", load("worker-primary", 0, 0, 4));
        selectable(schedulingViewRuntime, "worker-secondary", load("worker-secondary", 1, 0, 4));
        when(admissionRuntime.tryAcquireWorkerExclusiveLease("worker-primary")).thenReturn(false);
        when(admissionRuntime.tryAcquireWorkerExclusiveLease("worker-secondary")).thenReturn(true);

        WorkerSelectionResult result = owner.selectAndReserve(request(1, true));

        assertEquals(1, result.selectedCount());
        assertEquals("worker-secondary", result.selectedWorkers().getFirst().workerId());
        assertEquals(1, result.rejectedCountByReason().get("worker lock conflict"));
        verify(admissionRuntime).tryAcquireWorkerExclusiveLease("worker-primary");
        verify(admissionRuntime).tryAcquireWorkerExclusiveLease("worker-secondary");
    }

    @Test
    void selectedAccountingUsesGroupScopedSelectedEvidence() {
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(
                mock(WorkerSchedulingViewRuntime.class),
                admissionRuntime,
                scoreBandRuntime()
        );
        SelectedWorkerHandle handle = SelectedWorkerHandle.of("worker-1", GROUP_ID, TASK_ID, true);
        SelectedWorkerEvidence evidence = SelectedWorkerEvidence.of("worker-1", GROUP_ID, TASK_ID, false);
        assertTrue(owner.confirmSelected(handle));
        owner.recordSelectedClaimed(handle);
        owner.recordSelectedFinal(evidence);
        owner.releaseSelected(evidence);
        owner.releaseSelectedLock(evidence);
        owner.releaseSelectedLock(handle);

        verify(admissionRuntime).releaseWorkerExclusiveLease("worker-1");
    }

    @Test
    void scoreBandClaimCloseRequiresClaimObservation() {
        InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime =
                scoreBandRuntime(row("worker-1", Map.of("region", "us", "routingTags", "us")));
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(
                schedulingViewRuntime,
                admissionRuntime,
                scoreBandRuntime);
        groupIsReadable(schedulingViewRuntime);
        selectable(schedulingViewRuntime, "worker-1", load("worker-1", 0, 0, 4));

        WorkerSelectionResult result = owner.selectAndReserve(request(1, false));

        assertEquals(1, result.selectedCount());
        SelectedWorkerHandle handle = result.selectedWorkers().getFirst();
        assertNotNull(handle.scoreBandClaimScore());
        assertEquals(
                handle.scoreBandClaimScore(),
                scoreBandRuntime.slot(GROUP_ID, "worker-1").orElseThrow().score());

        owner.recordSelectedFinal(SelectedWorkerEvidence.of("worker-1", GROUP_ID, TASK_ID, false));
        assertEquals(
                handle.scoreBandClaimScore(),
                scoreBandRuntime.slot(GROUP_ID, "worker-1").orElseThrow().score());

        owner.recordSelectedFinal(handle.toEvidence());
        assertEquals(
                WorkerScoreBandKind.TIME_DUE,
                scoreBandRuntime.slot(GROUP_ID, "worker-1")
                        .orElseThrow()
                        .band(System.currentTimeMillis()));
    }

    @Test
    void staleScoreBandClaimObservationDoesNotReopenClaim() {
        InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime =
                scoreBandRuntime(row("worker-1", Map.of("region", "us", "routingTags", "us")));
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(
                schedulingViewRuntime,
                admissionRuntime,
                scoreBandRuntime);
        selectable(schedulingViewRuntime, "worker-1", load("worker-1", 0, 0, 4));
        long now = System.currentTimeMillis();
        long futureScore = WorkerScoreBand.futureScore(now + 60_000L);
        scoreBandRuntime.transition(WorkerScoreBandTransitionCommand.futureInterval(
                GROUP_ID,
                "worker-1",
                futureScore,
                "test claim",
                now));

        owner.recordSelectedFinal(new SelectedWorkerEvidence(
                "worker-1",
                GROUP_ID,
                TASK_ID,
                "stale-token",
                futureScore - 1,
                false));

        assertEquals(futureScore, scoreBandRuntime.slot(GROUP_ID, "worker-1").orElseThrow().score());
    }
}
