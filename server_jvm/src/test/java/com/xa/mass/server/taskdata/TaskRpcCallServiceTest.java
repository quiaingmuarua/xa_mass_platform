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
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.task.TaskRuntime.TaskDescriptor;
import com.xa.mass.kernel.task.TaskRuntime.TaskIdleDisposition;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.kernel.task.TaskRuntime.WorkerAllocationMechanism;
import com.xa.mass.server.api.v1.model.TaskItemRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallRequest;
import com.xa.mass.server.api.v1.model.TaskRpcCallResponse;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

class TaskRpcCallServiceTest {

    @Test
    void batchReturnsObservedAndNotObservedResultsWithoutReadingItemState() {
        TaskCallItemSubmission submission = mock(TaskCallItemSubmission.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        when(submission.submit(eq("task-1"), anyList()))
                .thenReturn(submitted("message-1", "message-2"));
        var loaded = new LinkedHashMap<String, String>();
        loaded.put("message-1", "{\"valid\":true}");
        loaded.put("message-2", null);
        when(taskRuntime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1", "message-2")
        )).thenReturn(loaded);

        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                service(submission, taskRuntime, registry, properties).call(
                        "task-1",
                        new TaskRpcCallRequest(
                                List.of(
                                        item("message-1", Map.of("n", 1)),
                                        item("message-2", Map.of("n", 2))
                                ),
                                1_000L
                        )
                );
        registry.shutdown();

        ResponseEntity<TaskRpcCallResponse> response = result(deferred);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().results().get("message-1").status()
                .wireValue()).isEqualTo("succeeded");
        assertThat(response.getBody().results().get("message-2").status()
                .wireValue()).isEqualTo("not_observed");
        verify(taskRuntime, never()).loadTaskItems(anyString(), anyList());
    }

    @Test
    void allObservedBatchReturnsOkAndUsesOneServerTimestamp() {
        TaskCallItemSubmission submission = mock(TaskCallItemSubmission.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        when(submission.submit(eq("task-1"), anyList()))
                .thenReturn(submitted("message-1", "message-2"));
        when(taskRuntime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1", "message-2")
        )).thenReturn(Map.of(
                "message-1", "one",
                "message-2", "two"
        ));

        DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred =
                service(submission, taskRuntime, registry, properties).call(
                        "task-1",
                        new TaskRpcCallRequest(
                                List.of(
                                        item("message-1", Map.of("n", 1)),
                                        item("message-2", Map.of("n", 2))
                                ),
                                1_000L
                        )
                );

        assertThat(result(deferred).getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskItem>> items = ArgumentCaptor.forClass(
                List.class
        );
        verify(submission).submit(eq("task-1"), items.capture());
        assertThat(items.getValue())
                .extracting(TaskItem::createdAtMillis)
                .containsOnly(1_000L);
        assertThat(items.getValue())
                .extracting(TaskItem::expireAtMillis)
                .containsOnly(2_000L);
        registry.shutdown();
    }

    @Test
    void duplicateMessageIdKeepsTheLatestItem() {
        TaskCallItemSubmission submission = mock(TaskCallItemSubmission.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(10);
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        when(submission.submit(eq("task-1"), anyList()))
                .thenReturn(submitted("message-1"));
        when(taskRuntime.loadTaskItemSuccessResults(
                "task-1",
                List.of("message-1")
        )).thenReturn(Map.of("message-1", "done"));

        service(submission, taskRuntime, registry, properties).call(
                "task-1",
                new TaskRpcCallRequest(
                        List.of(
                                item("message-1", Map.of("value", "old")),
                                item("message-1", Map.of("value", "new"))
                        ),
                        1_000L
                )
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskItem>> items = ArgumentCaptor.forClass(
                List.class
        );
        verify(submission).submit(eq("task-1"), items.capture());
        assertThat(items.getValue()).singleElement().satisfies(item ->
                assertThat(item.payload()).containsEntry("value", "new")
        );
        registry.shutdown();
    }

    @Test
    void capacityFailureDoesNotUndoAcceptedAppend() {
        TaskCallItemSubmission submission = mock(TaskCallItemSubmission.class);
        TaskRuntime taskRuntime = mock(TaskRuntime.class);
        TaskRpcProperties properties = properties(1);
        TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
        when(submission.submit(anyString(), anyList()))
                .thenAnswer(invocation -> {
                    List<TaskItem> items = invocation.getArgument(1);
                    return submitted(items.get(0).messageId());
                });
        when(taskRuntime.loadTaskItemSuccessResults(
                anyString(),
                anyList()
        )).thenReturn(Map.of());
        TaskRpcCallService service = service(
                submission,
                taskRuntime,
                registry,
                properties
        );

        service.call(
                "task-1",
                new TaskRpcCallRequest(
                        List.of(item("message-1", Map.of())),
                        1_000L
                )
        );
        assertThatThrownBy(() -> service.call(
                "task-2",
                new TaskRpcCallRequest(
                        List.of(item("message-2", Map.of())),
                        1_000L
                )
        )).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.TASK_RPC_CAPACITY_EXCEEDED
                )
        );
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
            TaskRpcWaitRegistry registry = new TaskRpcWaitRegistry(properties);
            when(submission.submit(eq("task-1"), anyList()))
                    .thenReturn(new TaskCallSubmissionResult(
                            entry.getKey(),
                            Map.of(),
                            "rejected"
                    ));
            TaskRpcCallService service = service(
                    submission,
                    taskRuntime,
                    registry,
                    properties
            );

            assertThatThrownBy(() -> service.call(
                    "task-1",
                    new TaskRpcCallRequest(
                            List.of(item("message-1", Map.of())),
                            1_000L
                    )
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

    private static TaskRpcCallService service(
            TaskCallItemSubmission submission,
            TaskRuntime taskRuntime,
            TaskRpcWaitRegistry registry,
            TaskRpcProperties properties
    ) {
        TaskResourceCatalog taskCatalog = mock(TaskResourceCatalog.class);
        when(taskCatalog.loadTaskAllocationDescriptors(anyList()))
                .thenAnswer(invocation -> {
                    List<String> taskIds = invocation.getArgument(0);
                    var descriptors = new LinkedHashMap<
                            String,
                            TaskDescriptor
                            >();
                    taskIds.forEach(taskId -> descriptors.put(
                            taskId,
                            callableDescriptor(taskId)
                    ));
                    return descriptors;
                });
        return new TaskRpcCallService(
                submission,
                taskRuntime,
                taskCatalog,
                registry,
                new TaskItemMapper(Clock.fixed(
                        Instant.ofEpochMilli(1_000),
                        ZoneOffset.UTC
                )),
                properties
        );
    }

    private static TaskDescriptor callableDescriptor(String taskId) {
        return new TaskDescriptor(
                taskId,
                "group-1",
                WorkerAllocationMechanism.DIRECT_ITEM_RULE,
                TaskIdleDisposition.PARK_WHEN_IDLE,
                null,
                Map.of(
                        "priority", "0",
                        "maximumCandidateWorkers", "1",
                        "maxRetryTimes", "3"
                )
        );
    }

    private static TaskItemRequest item(
            String messageId,
            Map<String, Object> payload
    ) {
        return new TaskItemRequest(
                messageId,
                "event",
                payload,
                5,
                1_000L,
                Map.of()
        );
    }

    private static TaskCallSubmissionResult submitted(String... messageIds) {
        var results = new LinkedHashMap<String, TaskItemAppendResult>();
        for (String messageId : messageIds) {
            results.put(
                    messageId,
                    new TaskItemAppendResult(TaskItemAppendStatus.APPENDED)
            );
        }
        return new TaskCallSubmissionResult(
                TaskCallSubmissionStatus.SUBMITTED,
                results,
                null
        );
    }

    @SuppressWarnings("unchecked")
    private static ResponseEntity<TaskRpcCallResponse> result(
            DeferredResult<ResponseEntity<TaskRpcCallResponse>> deferred
    ) {
        return (ResponseEntity<TaskRpcCallResponse>) deferred.getResult();
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
