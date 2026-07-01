package com.xa.mass.task.runtime.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xa.mass.task.runtime.AppendAdmissionPolicy;
import com.xa.mass.task.runtime.AppendBatchCommand;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.RuntimeEpoch;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeStarterBootstrapTest {

    @Test
    void memoryBackendStartsAndExposesPublicPorts() {
        try (var handle = TaskRuntimeStarter.start(TaskRuntimeBootstrapConfig.memory(), List.of())) {
            assertThat(handle.backendKind()).isEqualTo(TaskRuntimeBackendKind.MEMORY);
            assertThat(handle.status().running()).isTrue();

            var outcome = handle.runtime().appendBatch(new AppendBatchCommand(
                    "task-1",
                    List.of(new AppendItemInput("message-1", Map.of())),
                    new AppendAdmissionPolicy(10, AppendAdmissionPolicy.UNLIMITED_READY_BACKLOG),
                    RuntimeEpoch.of("task-1", 1L)));

            assertThat(outcome.status()).isEqualTo(AppendBatchStatus.ALL_ACCEPTED);
        }
    }

    @Test
    void redisBackendRequiresExplicitConnectionSettings() {
        assertThatThrownBy(() -> TaskRuntimeBootstrapConfig.redis("", "namespace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redisUri");
        assertThatThrownBy(() -> TaskRuntimeBootstrapConfig.redis("redis://127.0.0.1:6379/0", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("redisNamespace");
    }
}
