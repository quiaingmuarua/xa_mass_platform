package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.score.redis.RedisWorkerScoreCore;
import com.xa.mass.kernel.delivery.redis.RedisWorkerCommandRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import io.lettuce.core.RedisClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class KernelOwnerAssemblyTest {

    @Test
    void taskOperationsRouteOnlyToTheirDeclaredProviders() {
        HttpTaskRuntime control = mock(HttpTaskRuntime.class);
        RedisTaskRuntime data = mock(RedisTaskRuntime.class);
        AssembledTaskRuntime assembled =
                new AssembledTaskRuntime(control, data);
        TaskDescriptor descriptor = descriptor();
        TaskCreationResult created = new TaskCreationResult(
                TaskCreationStatus.CREATED
        );
        TaskItem item = new TaskItem(
                "message-1",
                "event",
                1,
                Map.of(),
                5,
                2L,
                null
        );
        when(control.createTask(descriptor, 0)).thenReturn(created);
        when(data.appendItems("task-1", List.of(item)))
                .thenReturn(Map.of());

        assertThat(assembled.createTask(descriptor, 0)).isSameAs(created);
        assertThat(assembled.appendItems("task-1", List.of(item)))
                .isEmpty();
        verify(control).createTask(descriptor, 0);
        verify(data).appendItems("task-1", List.of(item));
    }

    @Test
    void unavailableTaskOperationsDoNotFallback() {
        HttpTaskRuntime control = mock(HttpTaskRuntime.class);
        RedisTaskRuntime data = mock(RedisTaskRuntime.class);
        AssembledTaskRuntime assembled =
                new AssembledTaskRuntime(control, data);

        assertThatThrownBy(() -> assembled.loadTaskItems(
                "task-1",
                List.of("message-1")
        ))
                .isInstanceOf(KernelOperationNotImplementedException.class)
                .satisfies(error -> {
                    var notImplemented =
                            (KernelOperationNotImplementedException) error;
                    assertThat(notImplemented.contractName())
                            .isEqualTo("TaskRuntime");
                    assertThat(notImplemented.operationName())
                            .isEqualTo("load_task_items");
                });
        org.mockito.Mockito.verifyNoInteractions(control, data);
    }

    @Test
    void schedulingWorkerScoreOperationsRemainExplicitGaps() {
        RedisClient redisClient = mock(RedisClient.class);
        RedisWorkerScoreCore scoreCore =
                new RedisWorkerScoreCore(redisClient, "test");

        assertThatThrownBy(() ->
                scoreCore.acquireHotAcquireCandidates("group-1", null, 1))
                .isInstanceOf(KernelOperationNotImplementedException.class)
                .satisfies(error -> {
                    var notImplemented =
                            (KernelOperationNotImplementedException) error;
                    assertThat(notImplemented.contractName())
                            .isEqualTo("WorkerScoreCore");
                    assertThat(notImplemented.operationName())
                            .isEqualTo(
                                    "acquire_hot_acquire_candidates"
                            );
                });
        org.mockito.Mockito.verifyNoInteractions(redisClient);
    }

    @Test
    void authoritativeWorkerCommandAppendRemainsAnExplicitJvmGap() {
        RedisClient redisClient = mock(RedisClient.class);
        RedisWorkerCommandRuntime commands = new RedisWorkerCommandRuntime(
                redisClient,
                new WorkerDeliveryCodec(),
                "test"
        );

        assertThatThrownBy(() -> commands.appendWorkerCommands(
                "adapter-1",
                Map.of()
        ))
                .isInstanceOf(KernelOperationNotImplementedException.class)
                .satisfies(error -> {
                    var notImplemented =
                            (KernelOperationNotImplementedException) error;
                    assertThat(notImplemented.contractName())
                            .isEqualTo("WorkerCommandRuntime");
                    assertThat(notImplemented.operationName())
                            .isEqualTo("append_worker_commands");
                });
        org.mockito.Mockito.verifyNoInteractions(redisClient);
    }

    private static TaskDescriptor descriptor() {
        return new TaskDescriptor(
                "task-1",
                "group-1",
                WorkerAllocationMechanism.PRECOMPUTED_TASK_RULE,
                TaskIdleDisposition.CLOSE_WHEN_IDLE,
                Map.of(),
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
    }
}
