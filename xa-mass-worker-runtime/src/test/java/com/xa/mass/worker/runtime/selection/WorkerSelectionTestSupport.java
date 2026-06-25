package com.xa.mass.worker.runtime.selection;

import com.xa.mass.runtime.memory.InMemoryWorkerScoreBandSlotRuntime;
import com.xa.mass.runtime.worker.slot.WorkerScoreBand;
import com.xa.mass.runtime.worker.slot.WorkerScoreBandSlotMetadata;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateRow;
import com.xa.mass.worker.runtime.evidence.WorkerGroupCapabilityView;
import com.xa.mass.worker.runtime.evidence.WorkerLoadSnapshot;
import com.xa.mass.worker.runtime.evidence.WorkerSchedulingViewRuntime;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.Mockito.when;

final class WorkerSelectionTestSupport {

    static final String GROUP_ID = "group-a";
    static final String TASK_ID = "task-1";
    static final String PROJECT = "demoApp";
    static final String EVENT_CODE = "demo.event";

    private WorkerSelectionTestSupport() {
    }

    static WorkerSelectionRequest request(int requestedCount, boolean exclusiveWorkerLock) {
        return new WorkerSelectionRequest(
                TASK_ID,
                new WorkerSelectionIntent(
                        PROJECT,
                        EVENT_CODE,
                        List.of(GROUP_ID),
                        "us",
                        Map.of("region", "us"),
                        null,
                        Map.of()
                ),
                requestedCount,
                exclusiveWorkerLock
        );
    }

    static WorkerCandidateRow row(String workerId, Map<String, String> attributes) {
        return new WorkerCandidateRow(workerId, "agent-v1", GROUP_ID, "polling", attributes);
    }

    static InMemoryWorkerScoreBandSlotRuntime scoreBandRuntime(WorkerCandidateRow... rows) {
        InMemoryWorkerScoreBandSlotRuntime runtime = new InMemoryWorkerScoreBandSlotRuntime();
        long now = System.currentTimeMillis();
        for (WorkerCandidateRow row : rows) {
            runtime.upsert(
                    WorkerScoreBandSlotMetadata.worker(
                            row.workerGroupId(),
                            row.workerId(),
                            row.transportHint(),
                            row.attributes(),
                            1),
                    WorkerScoreBand.eligibleScore(now),
                    "test worker slot",
                    now);
        }
        return runtime;
    }

    static WorkerGroupCapabilityView groupView() {
        return new WorkerGroupCapabilityView(
                GROUP_ID,
                List.of(PROJECT),
                List.of(EVENT_CODE),
                Map.of(),
                1
        );
    }

    static WorkerLoadSnapshot load(String workerId, int activeLeaseCount, int reservedCount, int declaredCapacity) {
        return new WorkerLoadSnapshot(workerId, activeLeaseCount, reservedCount, declaredCapacity);
    }

    static void groupIsReadable(WorkerSchedulingViewRuntime schedulingViewRuntime) {
        when(schedulingViewRuntime.workerGroupReadView(GROUP_ID)).thenReturn(Optional.of(groupView()));
    }

    static void selectable(WorkerSchedulingViewRuntime schedulingViewRuntime,
                           String workerId,
                           WorkerLoadSnapshot load) {
        when(schedulingViewRuntime.isWorkerDispatchEnabled(workerId)).thenReturn(true);
        when(schedulingViewRuntime.hasWorkerExclusiveLease(workerId)).thenReturn(false);
        when(schedulingViewRuntime.getWorkerLoad(workerId)).thenReturn(load);
    }
}
