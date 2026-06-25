package com.xa.mass.worker.runtime.selection;

import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.GROUP_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.TASK_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.accepted;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.candidates;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.groupIsReadable;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.load;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.request;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.row;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.selectable;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.target;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerSelectionAtomicRuntimeTest {

    @Test
    void lockConflictReleasesReservationAndContinuesToNextSelectedWorker() {
        WorkerCandidateRuntime candidateRuntime = mock(WorkerCandidateRuntime.class);
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(candidateRuntime, schedulingViewRuntime, admissionRuntime);

        when(candidateRuntime.findWorkerCandidates(any(), anyInt())).thenReturn(candidates(
                row("worker-primary", Map.of("region", "us", "routingTags", "us")),
                row("worker-secondary", Map.of("region", "us", "routingTags", "us"))
        ));
        groupIsReadable(schedulingViewRuntime);
        selectable(schedulingViewRuntime, "worker-primary", load("worker-primary", 0, 0, 4));
        selectable(schedulingViewRuntime, "worker-secondary", load("worker-secondary", 1, 0, 4));
        when(admissionRuntime.reserveWorkerCapacity(target("worker-primary"))).thenReturn(accepted());
        when(admissionRuntime.tryAcquireWorkerExclusiveLease("worker-primary")).thenReturn(false);
        when(admissionRuntime.reserveWorkerCapacity(target("worker-secondary"))).thenReturn(accepted());
        when(admissionRuntime.tryAcquireWorkerExclusiveLease("worker-secondary")).thenReturn(true);

        WorkerSelectionResult result = owner.selectAndReserve(request(1, true));

        assertEquals(1, result.selectedCount());
        assertEquals("worker-secondary", result.selectedWorkers().getFirst().workerId());
        assertEquals(1, result.rejectedCountByReason().get("worker lock conflict"));
        verify(admissionRuntime).releaseWorkerReservation(target("worker-primary"));
        verify(admissionRuntime).reserveWorkerCapacity(target("worker-secondary"));
    }

    @Test
    void selectedAccountingUsesGroupScopedSelectedEvidence() {
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(
                mock(WorkerCandidateRuntime.class),
                mock(WorkerSchedulingViewRuntime.class),
                admissionRuntime
        );
        SelectedWorkerHandle handle = SelectedWorkerHandle.of("worker-1", GROUP_ID, TASK_ID, true);
        SelectedWorkerEvidence evidence = SelectedWorkerEvidence.of("worker-1", GROUP_ID, TASK_ID, false);
        when(admissionRuntime.confirmWorkerReservation(target("worker-1"))).thenReturn(true);

        assertTrue(owner.confirmSelected(handle));
        owner.recordSelectedClaimed(handle);
        owner.recordSelectedFinal(evidence);
        owner.releaseSelected(evidence);
        owner.releaseSelectedLock(evidence);
        owner.releaseSelectedLock(handle);

        verify(admissionRuntime).confirmWorkerReservation(target("worker-1"));
        verify(admissionRuntime).recordWorkClaimed(target("worker-1"));
        verify(admissionRuntime).recordWorkFinal(target("worker-1"));
        verify(admissionRuntime).releaseWorkerReservation(target("worker-1"));
        verify(admissionRuntime).releaseWorkerExclusiveLease("worker-1");
    }
}
