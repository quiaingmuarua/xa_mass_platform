package com.xa.mass.server.taskdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionStatus;
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
        TaskCallItemSubmission submission = mock(TaskCallItemSubmission.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties);
        when(submission.submit(eq("task-1"), anyList()))
                .thenReturn(submitted("message-1"));
        var missingResult = new java.util.LinkedHashMap<String, String>();
        missingResult.put("message-1", null);
        when(taskRuntime.loadTaskItemSuccessResults(
                "task-1",
                java.util.List.of("message-1")
        )).thenReturn(missingResult);

        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                new TaskRpcCallService(
                        submission,
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
        TaskCallItemSubmission submission = mock(TaskCallItemSubmission.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(1);
        TaskRpcWaitRegistry registry =
                new TaskRpcWaitRegistry(properties);
        when(submission.submit(anyString(), anyList()))
                .thenAnswer(invocation -> {
                    java.util.List<TaskRuntime.TaskItem> items =
                            invocation.getArgument(1);
                    return submitted(items.get(0).messageId());
                });
        when(taskRuntime.loadTaskItemSuccessResults(
                org.mockito.ArgumentMatchers.anyString(),
                anyList()
        )).thenReturn(Map.of());
        TaskRpcCallService service = new TaskRpcCallService(
                submission,
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
        verify(submission).submit(eq("task-2"), anyList());
        registry.shutdown();
    }

    @Test
    void submissionFailuresMapToExistingServerErrors() {
        Map<TaskCallSubmissionStatus, ServerErrorCode> expected = Map.of(
                TaskCallSubmissionStatus.NOT_FOUND,
                ServerErrorCode.TASK_NOT_FOUND,
                TaskCallSubmissionStatus.CLOSED,
                ServerErrorCode.KERNEL_REJECTED_CONFLICT,
                TaskCallSubmissionStatus.STALE,
                ServerErrorCode.KERNEL_REJECTED_CONFLICT,
                TaskCallSubmissionStatus.INVALID,
                ServerErrorCode.INVALID_TASK_DATA_REQUEST,
                TaskCallSubmissionStatus.RETRYABLE,
                ServerErrorCode.TASK_DATA_UNAVAILABLE
        );

        for (var entry : expected.entrySet()) {
            TaskCallItemSubmission submission =
                    mock(TaskCallItemSubmission.class);
            TaskRuntime taskRuntime = mock(TaskRuntime.class);
            TaskRpcProperties properties = properties(10);
            TaskRpcWaitRegistry registry =
                    new TaskRpcWaitRegistry(properties);
            when(submission.submit(eq("task-1"), anyList()))
                    .thenReturn(new TaskCallSubmissionResult(
                            entry.getKey(),
                            Map.of(),
                            "rejected"
                    ));
            TaskRpcCallService service = new TaskRpcCallService(
                    submission,
                    taskRuntime,
                    registry,
                    properties
            );

            assertThatThrownBy(() -> service.call(
                    "task-1",
                    new TaskRpcCallRequest(item(), 1_000L)
            )).isInstanceOfSatisfying(ServerException.class, error ->
                    assertThat(error.errorCode()).isEqualTo(entry.getValue())
            );
            verify(taskRuntime, never()).loadTaskItemSuccessResults(
                    anyString(),
                    anyList()
            );
            registry.shutdown();
        }
    }

    private static TaskItemRequest item() {
        return new TaskItemRequest(
                "message-1",
                "event",
                1,
                Map.of(),
                5,
                null,
                Map.of()
        );
    }

    private static TaskCallSubmissionResult submitted(String messageId) {
        return new TaskCallSubmissionResult(
                TaskCallSubmissionStatus.SUBMITTED,
                Map.of(
                        messageId,
                        new TaskItemAppendResult(
                                TaskItemAppendStatus.APPENDED
                        )
                ),
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
                250
        );
    }
}
