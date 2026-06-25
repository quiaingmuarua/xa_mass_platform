package com.xa.mass.worker.runtime.selection;

import com.xa.mass.runtime.worker.slot.WorkerScoreBandAcquireRequest;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotRuntime;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.GROUP_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.EVENT_CODE;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.PROJECT;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.TASK_ID;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.groupIsReadable;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.load;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.request;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.row;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.scoreBandRuntime;
import static com.xa.mass.worker.runtime.selection.WorkerSelectionTestSupport.selectable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkerSelectionRankingMechanicsTest {

    @Test
    void ranksByWorkerRuntimeLoadAndRoutingAffinityBeforeScoreBandClaim() {
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(
                schedulingViewRuntime,
                admissionRuntime,
                scoreBandRuntime(
                        row("worker-loaded", Map.of("region", "us", "routingTags", "lane-us")),
                        row("worker-no-affinity", Map.of("region", "us", "routingTags", "eu")),
                        row("worker-best", Map.of("region", "us", "routingTags", "lane-us")),
                        row("worker-blocked", Map.of("region", "us", "routingTags", "lane-us"))));
        groupIsReadable(schedulingViewRuntime);
        selectable(schedulingViewRuntime, "worker-loaded", load("worker-loaded", 3, 0, 3));
        selectable(schedulingViewRuntime, "worker-no-affinity", load("worker-no-affinity", 0, 0, 3));
        selectable(schedulingViewRuntime, "worker-best", load("worker-best", 0, 0, 3));
        when(schedulingViewRuntime.isWorkerDispatchEnabled("worker-blocked")).thenReturn(false);
        when(admissionRuntime.tryAcquireWorkerExclusiveLease("worker-best")).thenReturn(true);

        WorkerSelectionResult result = owner.selectAndReserve(requestWithRoutingCode("lane-us", 1));

        assertEquals(List.of("worker-best"), result.selectedWorkers().stream()
                .map(SelectedWorkerHandle::workerId)
                .toList());
        SelectedWorkerHandle selected = result.selectedWorkers().getFirst();
        assertEquals("demoApp:demo.event", selected.eventBindingKey());
        assertEquals("GROUP_SELECTOR", selected.workerCandidateSource());
        assertEquals("worker-best", selected.workerSchedulingResourceId());
        assertEquals("lane-us", selected.workerSchedulingRoutingTags());
        assertEquals(Map.of("region", "us", "routingTags", "lane-us"), selected.workerSchedulingAttributes());
        assertEquals(Boolean.TRUE, selected.workerSchedulingMatchesRoutingCode());
        assertEquals(0.0d, selected.candidateScore());
        assertEquals(0, selected.workerActiveLeaseCount());
        assertEquals(1, selected.workerReservedCount());
        assertEquals(3, selected.workerDeclaredCapacity());
        assertEquals(1.0d / 3.0d, selected.workerEstimatedLoadRatio());
        assertEquals(1, result.rejectedCountByReason().get("worker dispatch disabled"));
        verify(admissionRuntime).tryAcquireWorkerExclusiveLease("worker-best");
        verify(admissionRuntime, never()).tryAcquireWorkerExclusiveLease("worker-loaded");
        verify(admissionRuntime, never()).tryAcquireWorkerExclusiveLease("worker-no-affinity");
    }

    @Test
    void selectionRequestIsTranslatedToBoundedScoreBandAcquire() {
        WorkerSchedulingViewRuntime schedulingViewRuntime = mock(WorkerSchedulingViewRuntime.class);
        WorkerAdmissionRuntime admissionRuntime = mock(WorkerAdmissionRuntime.class);
        WorkerScoreBandSlotRuntime scoreBandSlotRuntime = mock(WorkerScoreBandSlotRuntime.class);
        WorkerSelectionOwner owner = new WorkerSelectionOwner(
                schedulingViewRuntime,
                admissionRuntime,
                scoreBandSlotRuntime);
        when(scoreBandSlotRuntime.acquire(any())).thenReturn(List.of());

        owner.selectAndReserve(request(2, false));

        ArgumentCaptor<WorkerScoreBandAcquireRequest> request =
                ArgumentCaptor.forClass(WorkerScoreBandAcquireRequest.class);
        verify(scoreBandSlotRuntime).acquire(request.capture());
        assertEquals(List.of(GROUP_ID), request.getValue().homeBucketIds());
        assertEquals(512, request.getValue().maxCount());
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
