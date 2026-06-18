package com.xa.mass.worker.runtime.selection;

import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRuntime;
import com.xa.mass.worker.runtime.candidate.WorkerTaskSelector;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.GROUP_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.EVENT_CODE;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.PROJECT;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.TASK_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.accepted;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.batch;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.groupIsReadable;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.load;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.request;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.row;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.selectable;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.target;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerSelectionRankingMechanicsTest {

    @Test
    void ranksByWorkerRuntimeLoadAndRoutingAffinityBeforeReserve() {
        WorkerCandidateRuntime candidateRuntime = mock(WorkerCandidateRuntime.class);
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(candidateRuntime, schedulingViewRuntime, admissionRuntime);

        when(candidateRuntime.findWorkerCandidateBatch(any(), anyInt())).thenReturn(batch(
                row("worker-loaded", Map.of("region", "us", "routingTags", "lane-us")),
                row("worker-no-affinity", Map.of("region", "us", "routingTags", "eu")),
                row("worker-best", Map.of("region", "us", "routingTags", "lane-us")),
                row("worker-offline", Map.of("region", "us", "routingTags", "lane-us"))
        ));
        groupIsReadable(schedulingViewRuntime);
        selectable(schedulingViewRuntime, "worker-loaded", load("worker-loaded", 3, 0, 3));
        selectable(schedulingViewRuntime, "worker-no-affinity", load("worker-no-affinity", 0, 0, 3));
        selectable(schedulingViewRuntime, "worker-best", load("worker-best", 0, 0, 3));
        when(schedulingViewRuntime.getWorkerReachability("worker-offline")).thenReturn(WorkerReachabilityState.OFFLINE);
        when(admissionRuntime.reserveWorkerCapacity(target("worker-best"))).thenReturn(accepted());
        when(admissionRuntime.tryAcquireWorkerExclusiveLease("worker-best")).thenReturn(true);

        WorkerSelectionResult result = owner.selectAndReserve(requestWithRoutingCode("lane-us", 1));

        assertEquals(List.of("worker-best"), result.selectedWorkers().stream()
                .map(SelectedWorkerHandle::workerId)
                .toList());
        assertEquals(1, result.rejectedCountByReason().get("worker transport unreachable"));
        verify(admissionRuntime).reserveWorkerCapacity(target("worker-best"));
        verify(admissionRuntime, never()).reserveWorkerCapacity(target("worker-loaded"));
        verify(admissionRuntime, never()).reserveWorkerCapacity(target("worker-no-affinity"));
    }

    @Test
    void selectionRequestIsTranslatedToBoundedWorkerTaskSelector() {
        WorkerCandidateRuntime candidateRuntime = mock(WorkerCandidateRuntime.class);
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(candidateRuntime, schedulingViewRuntime, admissionRuntime);
        when(candidateRuntime.findWorkerCandidateBatch(any(), anyInt())).thenReturn(batch());

        owner.selectAndReserve(request(2, false));

        ArgumentCaptor<WorkerTaskSelector> selector = ArgumentCaptor.forClass(WorkerTaskSelector.class);
        ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
        verify(candidateRuntime).findWorkerCandidateBatch(selector.capture(), limit.capture());
        assertEquals(WorkerSelectionTestSupport.TASK_ID, selector.getValue().taskId());
        assertEquals(List.of(GROUP_ID), selector.getValue().workerGroupIds());
        assertEquals(512, limit.getValue());
    }

    private static WorkerSelectionRequest requestWithRoutingCode(String routingCode, int requestedCount) {
        return new WorkerSelectionRequest(
                TASK_ID,
                new WorkerSelectionIntent(
                        PROJECT,
                        EVENT_CODE,
                        List.of(GROUP_ID),
                        routingCode,
                        Map.of("region", "us"),
                        null,
                        Map.of()
                ),
                requestedCount,
                true
        );
    }
}
