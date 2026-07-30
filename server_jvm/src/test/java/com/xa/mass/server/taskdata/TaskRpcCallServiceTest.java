package com.xa.mass.server.taskdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

class TaskRpcCallServiceTest {

    @Test
    void missingSuccessCompletesPendingWithoutReadingItemOrScoreState() {
        TaskDataService taskData = mock(TaskDataService.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties);
        when(taskData.appendTaskItem("task-1", item()))
                .thenReturn(new TaskItemAppendResult(
                        TaskItemAppendStatus.APPENDED
                ));
        var missingResult = new java.util.LinkedHashMap<String, String>();
        missingResult.put("message-1", null);
        when(taskRuntime.loadTaskItemSuccessResults(
                "task-1",
                java.util.List.of("message-1")
        )).thenReturn(missingResult);

        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new TaskRpcCallService(
                        taskData,
                        taskRuntime,
                        registry,
                        properties
                ).call(
                        "task-1",
                        new TaskRpcCallRequest(item(), 1_000L)
                );
        registry.shutdown();

        @SuppressWarnings("unchecked")
        ResponseEntity<TaskRpcCallResponse> response =
                (ResponseEntity<TaskRpcCallResponse>) deferred.getResult();
        assertThat(response).isNotNull();
        assertThat(response.getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().status().wireValue())
                .isEqualTo("pending");
        verify(taskRuntime, never()).loadTaskItems(
                org.mockito.ArgumentMatchers.anyString(),
                anyList()
        );
    }

    @Test
    void capacityFailureDoesNotUndoAcceptedAppend() {
        TaskDataService taskData = mock(TaskDataService.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(1);
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties);
        when(taskData.appendTaskItem(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        )).thenReturn(new TaskItemAppendResult(
                TaskItemAppendStatus.APPENDED
        ));
        when(taskRuntime.loadTaskItemSuccessResults(
                org.mockito.ArgumentMatchers.anyString(),
                anyList()
        )).thenReturn(Map.of());
        TaskRpcCallService service = new TaskRpcCallService(
                taskData,
                taskRuntime,
                registry,
                properties
        );

        service.call(
                "task-1",
                new TaskRpcCallRequest(item(), 1_000L)
        );
        assertThatThrownBy(() -> service.call(
                "task-2",
                new TaskRpcCallRequest(
                        new TaskItemRequest(
                                "message-2",
                                "event",
                                1,
                                Map.of(),
                                5,
                                null,
                                null
                        ),
                        1_000L
                )
        ))
                .isInstanceOf(ServerException.class)
                .satisfies(error -> assertThat(
                        ((ServerException) error).errorCode()
                ).isEqualTo(
                        ServerErrorCode.TASK_RPC_CAPACITY_EXCEEDED
                ));
        verify(taskData).appendTaskItem(
                "task-2",
                new TaskItemRequest(
                        "message-2",
                        "event",
                        1,
                        Map.of(),
                        5,
                        null,
                        null
                )
        );
        registry.shutdown();
    }

    private static TaskItemRequest item() {
        return new TaskItemRequest(
                "message-1",
                "event",
                1,
                Map.of(),
                5,
                null,
                null
        );
    }

    private static TaskRpcProperties properties(int maxWaiters) {
        return new TaskRpcProperties(
                30_000,
                60_000,
                maxWaiters,
                50,
                100,
                250,
                10_000,
                100
        );
    }
}
