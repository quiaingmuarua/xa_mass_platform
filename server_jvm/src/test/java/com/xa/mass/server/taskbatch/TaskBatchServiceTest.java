package com.xa.mass.server.taskbatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionResult;
import com.xa.mass.kernel.task.TaskCallItemSubmission.TaskCallSubmissionStatus;
import com.xa.mass.kernel.task.TaskRuntime.TaskItem;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendResult;
import com.xa.mass.kernel.task.TaskRuntime.TaskItemAppendStatus;
import com.xa.mass.server.api.v1.model.TaskItemResultsLoadResponse;
import com.xa.mass.server.api.v1.taskbatch.model.TaskBatchRunRequest;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.taskdata.TaskDataService;
import com.xa.mass.server.taskdata.WorkerGroupTaskCallRegistrationService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class TaskBatchServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void mapsRawLinesAppendsOnceAndKeepsBusinessInvalidResults()
            throws Exception {
        TaskDataService taskData = mock(TaskDataService.class);
        TaskCallItemSubmission submission = submission();
        when(taskData.loadTaskItemSuccessResults(
                eq("internal-task"),
                any()
        )).thenAnswer(invocation -> {
            List<String> messageIds = invocation.getArgument(1);
            Map<String, String> results = new LinkedHashMap<>();
            results.put(messageIds.get(0), "{\"valid\":false}");
            for (int index = 1; index < messageIds.size(); index++) {
                results.put(
                        messageIds.get(index),
                        "{\"anything\":\"ok\"}"
                );
            }
            return new TaskItemResultsLoadResponse(results);
        });
        TaskBatchService service = service(taskData, submission, Map.of(
                "string-group", "internal-task"
        ));
        service.upload(
                "strings.txt",
                " hello \n\nignored-tail-newline\n".getBytes(
                        StandardCharsets.UTF_8
                )
        );

        var response = service.run(new TaskBatchRunRequest(
                "string-group",
                "custom.event",
                "payload.value",
                "strings.txt",
                1000L
        ));

        assertThat(response.runId()).isEqualTo("task-batch-1786680000123");
        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(response.inputCount()).isEqualTo(3);
        assertThat(response.resultCount()).isEqualTo(3);
        assertThat(response.remainingCount()).isZero();
        assertThat(response.outputFile()).isEqualTo(
                "task-batch-1786680000123.jsonl"
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskItem>> submittedItems =
                ArgumentCaptor.forClass(List.class);
        verify(submission).submit(eq("internal-task"), submittedItems.capture());
        assertThat(submittedItems.getValue()).hasSize(3);
        assertThat(submittedItems.getValue().get(0).payload())
                .containsEntry("payload.value", " hello ");
        assertThat(submittedItems.getValue().get(1).payload())
                .containsEntry("payload.value", "");
        assertThat(submittedItems.getValue().get(2).payload())
                .containsEntry("payload.value", "ignored-tail-newline");
        assertThat(submittedItems.getValue())
                .allSatisfy(item -> {
                    assertThat(item.eventCode()).isEqualTo("custom.event");
                    assertThat(item.priority()).isEqualTo(5);
                    assertThat(item.allocationRule()).isEmpty();
                });

        String output = new String(
                service.download(response.outputFile()),
                StandardCharsets.UTF_8
        );
        assertThat(output.lines()).hasSize(3);
        assertThat(output).contains("\"valid\":false");
    }

    @Test
    void emptyInputPublishesWithoutTaskDataAndMissingGroupIsNotFound()
            throws Exception {
        TaskDataService taskData = mock(TaskDataService.class);
        TaskCallItemSubmission submission = submission();
        TaskBatchService service = service(taskData, submission, Map.of(
                "string-group", "internal-task"
        ));
        service.upload("empty.txt", new byte[0]);

        var response = service.run(new TaskBatchRunRequest(
                "string-group",
                "extension.worker.string.md5",
                "value",
                "empty.txt",
                null
        ));

        assertThat(response.status()).isEqualTo("succeeded");
        assertThat(response.inputCount()).isZero();
        assertThat(service.download(response.outputFile())).isEmpty();
        verify(submission, never()).submit(any(), any());
        verify(taskData, never()).loadTaskItemSuccessResults(any(), any());

        assertThatThrownBy(() -> service.run(new TaskBatchRunRequest(
                "missing-group",
                "extension.worker.string.md5",
                "value",
                "empty.txt",
                null
        ))).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.TASK_BATCH_RESOURCE_NOT_FOUND
                )
        );
    }

    @Test
    void thousandLinesUseTenBoundedKernelSubmissions() throws Exception {
        TaskDataService taskData = mock(TaskDataService.class);
        TaskCallItemSubmission submission = submission();
        when(taskData.loadTaskItemSuccessResults(eq("internal-task"), any()))
                .thenAnswer(invocation -> {
                    List<String> messageIds = invocation.getArgument(1);
                    Map<String, String> results = new LinkedHashMap<>();
                    messageIds.forEach(messageId -> results.put(
                            messageId,
                            "{\"valid\":true}"
                    ));
                    return new TaskItemResultsLoadResponse(results);
                });
        TaskBatchService service = service(taskData, submission, Map.of(
                "string-group", "internal-task"
        ));
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 1000; index++) {
            if (index > 0) {
                content.append('\n');
            }
            content.append("line-").append(index);
        }
        service.upload(
                "thousand.txt",
                content.toString().getBytes(StandardCharsets.UTF_8)
        );

        var response = service.run(new TaskBatchRunRequest(
                "string-group",
                "not-in-advisory-catalog",
                "value",
                "thousand.txt",
                1000L
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskItem>> chunks =
                ArgumentCaptor.forClass(List.class);
        verify(submission, times(10)).submit(eq("internal-task"), chunks.capture());
        assertThat(chunks.getAllValues())
                .hasSize(10)
                .allSatisfy(chunk -> assertThat(chunk).hasSize(100));
        assertThat(response.resultCount()).isEqualTo(1000);
    }

    @Test
    void laterChunkFailureKeepsEarlierSubmissionNonTransactional()
            throws Exception {
        TaskDataService taskData = mock(TaskDataService.class);
        TaskCallItemSubmission submission =
                mock(TaskCallItemSubmission.class);
        AtomicInteger calls = new AtomicInteger();
        when(submission.submit(eq("internal-task"), any()))
                .thenAnswer(invocation -> {
                    List<TaskItem> items = invocation.getArgument(1);
                    if (calls.getAndIncrement() == 0) {
                        return submitted(items);
                    }
                    return new TaskCallSubmissionResult(
                            TaskCallSubmissionStatus.CLOSED,
                            Map.of(),
                            "Task is already closed"
                    );
                });
        TaskBatchService service = service(taskData, submission, Map.of(
                "string-group", "internal-task"
        ));
        String content = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> "line-" + index)
                .collect(java.util.stream.Collectors.joining("\n"));
        service.upload(
                "chunk-failure.txt",
                content.getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.run(new TaskBatchRunRequest(
                "string-group",
                "extension.worker.string.md5",
                "value",
                "chunk-failure.txt",
                1_000L
        ))).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.TASK_BATCH_CONFLICT
                )
        );
        verify(submission, times(2)).submit(eq("internal-task"), any());
        verify(taskData, never()).loadTaskItemSuccessResults(any(), any());
    }

    @Test
    void malformedResultFailsWithoutPublishingOutput() throws Exception {
        TaskDataService taskData = mock(TaskDataService.class);
        when(taskData.loadTaskItemSuccessResults(eq("internal-task"), any()))
                .thenAnswer(invocation -> {
                    List<String> messageIds = invocation.getArgument(1);
                    return new TaskItemResultsLoadResponse(Map.of(
                            messageIds.get(0),
                            "[]"
                    ));
                });
        TaskBatchService service = service(taskData, Map.of(
                "string-group", "internal-task"
        ));
        service.upload(
                "invalid-result.txt",
                "line".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> service.run(new TaskBatchRunRequest(
                "string-group",
                "extension.worker.string.md5",
                "value",
                "invalid-result.txt",
                1000L
        ))).isInstanceOfSatisfying(ServerException.class, error ->
                assertThat(error.errorCode()).isEqualTo(
                        ServerErrorCode.TASK_BATCH_UNAVAILABLE
                )
        );
        Path output = temporaryDirectory.resolve("data")
                .resolve("rpc-task")
                .resolve("output");
        try (var children = Files.list(output)) {
            assertThat(children.toList()).isEmpty();
        }
    }

    @Test
    void deadlinePublishesPartialAndConcurrentRunsUseDifferentCoordinates()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        TaskDataService taskData = mock(TaskDataService.class);
        when(taskData.loadTaskItemSuccessResults(eq("internal-task"), any()))
                .thenAnswer(invocation -> {
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return new TaskItemResultsLoadResponse(Map.of());
                });
        TaskBatchService service = service(taskData, Map.of(
                "string-group", "internal-task"
        ));
        service.upload("strings.txt", "hello".getBytes(StandardCharsets.UTF_8));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.run(request(1L)));
            var second = executor.submit(() -> service.run(request(1L)));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            var firstResult = first.get(5, TimeUnit.SECONDS);
            var secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.status()).isEqualTo("partial");
            assertThat(secondResult.status()).isEqualTo("partial");
            assertThat(firstResult.runId()).isNotEqualTo(secondResult.runId());
            assertThat(firstResult.outputFile())
                    .isNotEqualTo(secondResult.outputFile());
        }
    }

    private TaskBatchRunRequest request(long waitMillis) {
        return new TaskBatchRunRequest(
                "string-group",
                "extension.worker.string.md5",
                "value",
                "strings.txt",
                waitMillis
        );
    }

    private TaskBatchService service(
            TaskDataService taskData,
            Map<String, String> tasks
    ) throws Exception {
        return service(taskData, submission(), tasks);
    }

    private TaskBatchService service(
            TaskDataService taskData,
            TaskCallItemSubmission submission,
            Map<String, String> tasks
    ) throws Exception {
        TaskBatchProperties properties = new TaskBatchProperties(
                temporaryDirectory.resolve("data")
                        .resolve("rpc-task")
                        .toString(),
                16 * 1024,
                1000,
                1,
                30_000,
                300_000
        );
        WorkerGroupTaskCallRegistrationService registrations =
                mock(WorkerGroupTaskCallRegistrationService.class);
        when(registrations.requireRegisteredTaskId(any())).thenAnswer(
                invocation -> {
                    String workerGroupId = invocation.getArgument(0);
                    String taskId = tasks.get(workerGroupId);
                    if (taskId != null) {
                        return taskId;
                    }
                    throw new ServerException(
                            ServerErrorCode.TASK_CALL_NOT_REGISTERED,
                            "taskCall.resolve",
                            null,
                            null
                    );
                }
        );
        return new TaskBatchService(
                TaskBatchFileStore.open(properties),
                taskData,
                submission,
                registrations,
                properties,
                Clock.fixed(
                        Instant.ofEpochMilli(1_786_680_000_123L),
                        ZoneOffset.UTC
                )
        );
    }

    private static TaskCallItemSubmission submission() {
        TaskCallItemSubmission submission = mock(TaskCallItemSubmission.class);
        when(submission.submit(eq("internal-task"), any()))
                .thenAnswer(invocation -> submitted(invocation.getArgument(1)));
        return submission;
    }

    private static TaskCallSubmissionResult submitted(List<TaskItem> items) {
        Map<String, TaskItemAppendResult> results = new LinkedHashMap<>();
        items.forEach(item -> results.put(
                item.messageId(),
                new TaskItemAppendResult(TaskItemAppendStatus.APPENDED)
        ));
        return new TaskCallSubmissionResult(
                TaskCallSubmissionStatus.SUBMITTED,
                results,
                null
        );
    }
}
