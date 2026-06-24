package com.xa.mass.worker.runtime.selection;

import com.xa.mass.worker.runtime.admission.WorkerAdmissionResult;
import com.xa.mass.worker.runtime.admission.WorkerAdmissionTarget;
import com.xa.mass.worker.runtime.candidate.WorkerCandidateBatch;
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

    static WorkerCandidateBatch<WorkerCandidateRow> batch(WorkerCandidateRow... rows) {
        return new WorkerCandidateBatch<>(List.of(rows), 0, rows.length, 0, 0);
    }

    static WorkerAdmissionTarget target(String workerId) {
        return WorkerAdmissionTarget.groupScoped(GROUP_ID, workerId, TASK_ID);
    }

    static WorkerAdmissionResult accepted() {
        return WorkerAdmissionResult.acceptedResult();
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
