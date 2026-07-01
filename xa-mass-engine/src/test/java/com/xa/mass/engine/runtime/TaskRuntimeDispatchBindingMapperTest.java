package com.xa.mass.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.UserRef;
import com.xa.mass.task.runtime.ClaimedWorkItem;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskRuntimeDispatchBindingMapperTest {

    @Test
    void mapsTaskRuntimeClaimToTransportDispatchBinding() {
        var task = task("task-1", "demoApp", Map.of(TaskSharedConfig.WORKER_GROUP_ID, "group-1"));
        var claimed = new ClaimedWorkItem(
                "task-1",
                "message-1",
                "demo.event",
                Map.of("value", 1),
                "payload-ref-1",
                "lease-1",
                "selection-token-1",
                123L,
                "worker-1",
                "group-1",
                "batch-1",
                2,
                5_000L);

        var binding = TaskRuntimeDispatchBindingMapper.fromTaskRuntimeClaim(task, claimed);

        assertThat(binding.taskId()).isEqualTo("task-1");
        assertThat(binding.messageId()).isEqualTo("message-1");
        assertThat(binding.eventCode()).isEqualTo("demo.event");
        assertThat(binding.payload()).containsEntry("value", 1);
        assertThat(binding.payloadRef()).isEqualTo("payload-ref-1");
        assertThat(binding.retryCount()).isEqualTo(1);
        assertThat(binding.attemptNo()).isEqualTo(2);
        assertThat(binding.attemptId()).isEqualTo("runtime-attempt-message-1-2-worker-1-batch-1");
        assertThat(binding.leaseToken()).isEqualTo("lease-1");
        assertThat(binding.workerId()).isEqualTo("worker-1");
        assertThat(binding.workerGroupId()).isEqualTo("group-1");
        assertThat(binding.batchId()).isEqualTo("batch-1");
        assertThat(binding.selectionToken()).isEqualTo("selection-token-1");
        assertThat(binding.scoreBandClaimScore()).isEqualTo(123L);
        assertThat(binding.eventBindingKey()).isEqualTo("demoApp:demo.event");
        assertThat(binding.workerCandidateSource()).isEqualTo("GROUP_SELECTOR");
    }

    @Test
    void fallsBackToTaskEventCodeWhenClaimDoesNotCarryOne() {
        var task = task("task-1", "demoApp", Map.of(
                TaskSharedConfig.WORKER_GROUP_ID, "group-1",
                TaskSharedConfig.SDK_METADATA, Map.of(TaskSharedConfig.SDK_EVENT_CODE, "task.event")));
        var claimed = new ClaimedWorkItem(
                "task-1",
                "message-1",
                null,
                Map.of(),
                null,
                "lease-1",
                "selection-token-1",
                "worker-1",
                "group-1",
                "batch-1",
                1,
                5_000L);

        var binding = TaskRuntimeDispatchBindingMapper.fromTaskRuntimeClaim(task, claimed);

        assertThat(binding.eventCode()).isEqualTo("task.event");
        assertThat(binding.eventBindingKey()).isEqualTo("demoApp:task.event");
    }

    private static Task task(String taskId, String project, Map<String, Object> sharedConfig) {
        return new Task(taskId, "task", project, 1, sharedConfig, UserRef.of("agent"));
    }
}
