package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkerLabConvergenceSupportTest {

    @Test
    void fixesTwoFiveHundredWorkerInventoryGroups() {
        assertThat(WorkerLabConvergenceSupport.PHONE_WORKERS).hasSize(500);
        assertThat(WorkerLabConvergenceSupport.STRING_WORKERS).hasSize(500);
        assertThat(WorkerLabConvergenceSupport.CONVERGENCE_WORKERS)
                .hasSize(1000);
        assertThat(WorkerLabConvergenceSupport.PHONE_WORKERS.get(99)
                .labWorkerKey()).isEqualTo("workers-000.jsonl:100");
        assertThat(WorkerLabConvergenceSupport.PHONE_WORKERS.get(100)
                .labWorkerKey()).isEqualTo("workers-001.jsonl:1");
        assertThat(WorkerLabConvergenceSupport.STRING_WORKERS.get(499)
                .labWorkerKey()).isEqualTo("workers-004.jsonl:100");
    }

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
