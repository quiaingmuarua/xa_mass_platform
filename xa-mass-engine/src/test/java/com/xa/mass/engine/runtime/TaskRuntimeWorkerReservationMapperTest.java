package com.xa.mass.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.task.runtime.WorkerReservationEvidence;
import com.xa.mass.worker.runtime.selection.SelectedWorkerHandle;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRuntimeWorkerReservationMapperTest {

    @Test
    void mapsSelectedWorkerHandleToReservationEvidence() {
        var selectedWorker = SelectedWorkerHandle.of("worker-1", "group-1", "scope-1", true);

        var evidence = TaskRuntimeWorkerReservationMapper.toReservationEvidence(selectedWorker, "batch-1");

        assertThat(evidence.workerId()).isEqualTo("worker-1");
        assertThat(evidence.workerGroupId()).isEqualTo("group-1");
        assertThat(evidence.reservationToken()).isEqualTo(selectedWorker.selectionToken());
        assertThat(evidence.dispatchTargetRef()).isNull();
        assertThat(evidence.batchId()).isEqualTo("batch-1");
    }

    @Test
    void mapsSelectedWorkersInOrder() {
        var evidence = TaskRuntimeWorkerReservationMapper.toReservationEvidence(List.of(
                SelectedWorkerHandle.of("worker-1", "group-1", "scope-1", true),
                SelectedWorkerHandle.of("worker-2", "group-1", "scope-1", false)));

        assertThat(evidence)
                .extracting(WorkerReservationEvidence::workerId)
                .containsExactly("worker-1", "worker-2");
    }
}
