package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerLabConvergenceSupportTest {

    @Test
    void describesOnlyMissingUnexpectedAndUnacceptedWorkerStates() {
        assertThat(WorkerLabConvergenceSupport.describeUnexpectedStates(
                List.of("worker-1", "worker-2", "worker-3"),
                Map.of(
                        "worker-1", "hot-score-overdue",
                        "worker-2", "recovery",
                        "worker-4", "cold"
                ),
                WorkerLabConvergenceSupport::isHotSchedulingState
        )).isEqualTo(
                "unexpectedStates={worker-2=recovery, worker-3=missing, "
                        + "worker-4=unexpected:cold}"
        );
    }
}
