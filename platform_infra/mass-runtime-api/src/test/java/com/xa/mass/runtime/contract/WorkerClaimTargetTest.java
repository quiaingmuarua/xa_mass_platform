package com.xa.mass.runtime.contract;

import com.xa.mass.runtime.api.WorkerClaimTarget;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerClaimTargetTest {

    @Test
    void workerLevelFactoryCreatesClaimTarget() {
        WorkerClaimTarget target = WorkerClaimTarget.workerLevel(
                "worker-1",
                "batch-1",
                3,
                Set.of("event.a")
        );

        assertThat(target.workerId()).isEqualTo("worker-1");
        assertThat(target.batchId()).isEqualTo("batch-1");
        assertThat(target.capacity()).isEqualTo(3);
        assertThat(target.supportsEvent("event.a")).isTrue();
        assertThat(target.supportsEvent("event.b")).isFalse();
    }

    @Test
    void groupScopedFactoryCarriesWorkerGroupEvidence() {
        WorkerClaimTarget target = WorkerClaimTarget.groupScoped(
                " group-a ",
                "worker-1",
                "batch-1",
                2,
                Set.of(" event.a ")
        );

        assertThat(target.workerGroupId()).isEqualTo("group-a");
        assertThat(target.workerId()).isEqualTo("worker-1");
        assertThat(target.batchId()).isEqualTo("batch-1");
        assertThat(target.capacity()).isEqualTo(2);
        assertThat(target.supportsEvent("event.a")).isTrue();
    }
}
