package com.xa.mass.engine.runtime;

import com.xa.mass.task.runtime.WorkerReservationEvidence;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;

import java.util.List;

public final class TaskRuntimeWorkerReservationMapper {

    private TaskRuntimeWorkerReservationMapper() {
    }

    public static List<WorkerReservationEvidence> toReservationEvidence(List<SelectedWorkerHandle> selectedWorkers) {
        if (selectedWorkers == null || selectedWorkers.isEmpty()) {
            return List.of();
        }
        return selectedWorkers.stream()
                .map(TaskRuntimeWorkerReservationMapper::toReservationEvidence)
                .toList();
    }

    public static WorkerReservationEvidence toReservationEvidence(SelectedWorkerHandle selectedWorker) {
        return toReservationEvidence(selectedWorker, null);
    }

    public static WorkerReservationEvidence toReservationEvidence(SelectedWorkerHandle selectedWorker, String batchId) {
        if (selectedWorker == null) {
            throw new IllegalArgumentException("selectedWorker is required");
        }
        return new WorkerReservationEvidence(
                selectedWorker.workerId(),
                selectedWorker.workerGroupId(),
                selectedWorker.selectionToken(),
                null,
                batchId,
                selectedWorker.scoreBandClaimScore());
    }
}
