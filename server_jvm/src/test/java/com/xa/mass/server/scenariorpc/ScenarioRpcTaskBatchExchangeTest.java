package com.xa.mass.server.scenariorpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.scenariorpc.ScenarioRpcDescriptor;
import com.xa.mass.scenariorpc.ScenarioRpcItem;
import com.xa.mass.server.api.v1.model.CommandResultResponse;
import com.xa.mass.server.api.v1.model.RuntimeCommandStatus;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.model.TaskItemsAppendRequest;
import com.xa.mass.server.api.v1.model.TaskItemsAppendResponse;
import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ScenarioRpcTaskBatchExchangeTest {

    @Test
    void appendsTheWholeScenarioBatchAndLoadsPendingResults() {
        TaskDataService taskData = mock(TaskDataService.class);
        WorkerGroupTaskCatalog catalog = mock(WorkerGroupTaskCatalog.class);
        when(catalog.taskIdsByWorkerGroup()).thenReturn(Map.of(
                "string-group",
                "internal-task"
        ));
        when(taskData.appendTaskItems(
                eq("internal-task"),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new TaskItemsAppendResponse(Map.of(
                "message-1",
                new CommandResultResponse(
                        RuntimeCommandStatus.APPENDED,
                        null
                ),
                "message-2",
                new CommandResultResponse(
                        RuntimeCommandStatus.APPENDED,
                        null
                )
        )));
        when(taskData.loadTaskItemSuccessResults(
                "internal-task",
                List.of("message-1", "message-2")
        )).thenReturn(new TaskItemResultsLoadResponse(Map.of(
                "message-1",
                "{\"valid\":true,\"md5\":\"hash\"}"
        )));
        ScenarioRpcTaskBatchExchange exchange = newExchange(
                taskData,
                catalog
        );
        ScenarioRpcDescriptor descriptor = new ScenarioRpcDescriptor(
                "string.md5",
                "string-group",
                "string.md5"
        );

        exchange.append(descriptor, List.of(
                new ScenarioRpcItem("message-1", Map.of("value", "a")),
                new ScenarioRpcItem("message-2", Map.of("value", "b"))
        ));
        Map<String, Map<String, Object>> loaded = exchange.loadResults(
                descriptor,
                List.of("message-1", "message-2")
        );

        ArgumentCaptor<TaskItemsAppendRequest> request =
                ArgumentCaptor.forClass(TaskItemsAppendRequest.class);
        verify(taskData).appendTaskItems(
                eq("internal-task"),
                request.capture()
        );
        assertThat(request.getValue().items()).hasSize(2);
        assertThat(request.getValue().items().getFirst().eventCode())
                .isEqualTo("string.md5");
        assertThat(request.getValue().items().getFirst().allocationRule())
                .isEmpty();
        assertThat(loaded).containsOnlyKeys("message-1");
        assertThat(loaded.get("message-1").get("md5")).isEqualTo("hash");
    }

    @Test
    void rejectsMissingTaskOrUnacceptedAppend() {
        TaskDataService taskData = mock(TaskDataService.class);
        WorkerGroupTaskCatalog missing = mock(WorkerGroupTaskCatalog.class);
        when(missing.taskIdsByWorkerGroup()).thenReturn(Map.of());
        ScenarioRpcDescriptor descriptor = new ScenarioRpcDescriptor(
                "string.md5",
                "string-group",
                "string.md5"
        );
        ScenarioRpcItem item = new ScenarioRpcItem(
                "message-1",
                Map.of("value", "a")
        );
        assertThatThrownBy(() -> newExchange(taskData, missing).append(
                descriptor,
                List.of(item)
        )).isInstanceOf(IllegalStateException.class);

        WorkerGroupTaskCatalog catalog = mock(WorkerGroupTaskCatalog.class);
        when(catalog.taskIdsByWorkerGroup()).thenReturn(Map.of(
                "string-group",
                "internal-task"
        ));
        when(taskData.appendTaskItems(
                eq("internal-task"),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new TaskItemsAppendResponse(Map.of(
                "message-1",
                new CommandResultResponse(
                        RuntimeCommandStatus.RETRYABLE,
                        null
                )
        )));
        assertThatThrownBy(() -> newExchange(taskData, catalog).append(
                descriptor,
                List.of(item)
        )).isInstanceOf(IllegalStateException.class);
    }

    private static ScenarioRpcTaskBatchExchange newExchange(
            TaskDataService taskData,
            WorkerGroupTaskCatalog catalog
    ) {
        return new ScenarioRpcTaskBatchExchange(
                taskData,
                catalog,
                Clock.fixed(
                        Instant.ofEpochMilli(1_786_680_000_000L),
                        ZoneOffset.UTC
                )
        );
    }
}
