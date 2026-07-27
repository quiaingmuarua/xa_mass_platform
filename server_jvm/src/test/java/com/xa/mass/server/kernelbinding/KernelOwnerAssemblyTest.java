package com.xa.mass.server.kernelbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskCreationStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskType;
import com.xa.mass.kernel.task.redis.RedisTaskRuntime;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerGroupDescriptor;
import com.xa.mass.kernel.worker.redis.RedisWorkerResourceCatalog;
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
    void unavailableWorkerCatalogOperationsDoNotFallback() {
        HttpWorkerResourceCatalog control =
                mock(HttpWorkerResourceCatalog.class);
        RedisWorkerResourceCatalog reads =
                mock(RedisWorkerResourceCatalog.class);
        AssembledWorkerResourceCatalog assembled =
                new AssembledWorkerResourceCatalog(control, reads);

        assertThatThrownBy(() -> assembled.getWorkerDescriptors(
                "group-1",
                List.of("worker-1")
        ))
                .isInstanceOf(KernelOperationNotImplementedException.class)
                .satisfies(error -> {
                    var notImplemented =
                            (KernelOperationNotImplementedException) error;
                    assertThat(notImplemented.contractName())
                            .isEqualTo("WorkerResourceCatalog");
                    assertThat(notImplemented.operationName())
                            .isEqualTo("get_worker_descriptors");
                });
        verifyNoInteractions(control, reads);
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
        verifyNoInteractions(control, data);
    }

    private static TaskDescriptor descriptor() {
        return new TaskDescriptor(
                "task-1",
                "group-1",
                TaskType.TASK_DRIVEN,
                Map.of(),
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                ),
                0L
        );
    }
}
