package com.xa.mass.server.task.call;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;

import com.xa.mass.kernel.task.TaskCallItemSubmission;
import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.server.api.v1.contract.task.TaskItemRequest;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.task.TaskItemMapper;
import com.xa.mass.server.task.TaskItemOutcomeProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TaskCallSubmissionServiceTest {
    @Test void itemFunctionCanDifferFromTheTasksSupplyRule() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var matching = mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        var descriptor = new TaskRuntime.TaskDescriptor("task", "group",
                TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "3"),
                "worker.country", List.of(new RefillTarget(Map.of(), 100)));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor));
        var raw = new WorkerQuery("worker.messaging.available", Map.of("phone", "+8613800000000"));
        when(matching.normalizeQuery("group", raw)).thenReturn(raw);
        when(submission.submit(eq("task"), anyList())).thenReturn(new TaskCallItemSubmission.TaskCallSubmissionResult(
                TaskCallItemSubmission.TaskCallSubmissionStatus.SUBMITTED,
                Map.of("id", new TaskRuntime.TaskItemAppendResult(TaskRuntime.TaskItemAppendStatus.APPENDED)), null));
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), matching);
        org.assertj.core.api.Assertions.assertThat(service.submit("task", List.of(
                new TaskItemRequest("id", "event", Map.of(), 5, 1000L, raw)))).containsExactly("id");
        verify(submission).submit(eq("task"), argThat(items -> raw.equals(items.getFirst().workerSelector())));
        verify(matching).normalizeQuery("group", raw);
        verifyNoMoreInteractions(matching);
    }

    @Test void matchingFailureRejectsCallWithoutSubmission() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var matching = mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of(
                "task", new TaskRuntime.TaskDescriptor("task", "group",
                        TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE,
                        Map.of("priority", "0", "maxRetryTimes", "3"), "worker.default", java.util.List.of(new com.xa.mass.kernel.assignment.RefillTarget(java.util.Map.of(), 100)))));

        when(matching.normalizeQuery(anyString(),any())).thenThrow(new IllegalStateException("unavailable"));
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), matching);
        var item = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, new WorkerQuery("worker.default", Map.of()));
        assertThatThrownBy(() -> service.submit("task", List.of(item))).isInstanceOf(ServerException.class);
        verifyNoInteractions(submission);
        verify(matching).normalizeQuery("group", item.workerSelector());
        verifyNoMoreInteractions(matching);
    }

    @Test void matchingAdmissionValidatesOverwrittenInputsAndPersistsTheSelectorWithoutSelection() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var matching = mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(matching.normalizeQuery(eq("group"),any())).thenAnswer(call -> call.getArgument(1));

        var descriptor = new TaskRuntime.TaskDescriptor("task", "group", TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "3"), "worker.default", java.util.List.of(new com.xa.mass.kernel.assignment.RefillTarget(java.util.Map.of(), 100)));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor));
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), matching);
        var cn = new WorkerQuery("worker.default", Map.of("country", List.of("CN")));
        var malformed = new WorkerQuery("worker.default", Map.of("country", List.of("cn")));
        doThrow(new IllegalArgumentException("invalid country")).when(matching).normalizeQuery("group",malformed);
        var bad = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, new WorkerQuery("worker.default", Map.of("country", List.of("cn"))));
        var good = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, new WorkerQuery("worker.default", Map.of("country", List.of("CN"))));
        assertThatThrownBy(() -> service.submit("task", List.of(bad, good))).isInstanceOf(ServerException.class);
        verifyNoInteractions(submission);
        doThrow(new IllegalArgumentException("index disabled")).when(matching).normalizeQuery("group",cn);
        assertThatThrownBy(() -> service.submit("task", List.of(good))).isInstanceOf(ServerException.class);
        verifyNoInteractions(submission);
        doReturn(cn).when(matching).normalizeQuery("group",cn);
        when(submission.submit(eq("task"), anyList())).thenReturn(new TaskCallItemSubmission.TaskCallSubmissionResult(
                TaskCallItemSubmission.TaskCallSubmissionStatus.SUBMITTED,
                Map.of("id", new TaskRuntime.TaskItemAppendResult(TaskRuntime.TaskItemAppendStatus.APPENDED)), null));
        org.assertj.core.api.Assertions.assertThat(service.submit("task", List.of(good))).containsExactly("id");
        verify(submission).submit(eq("task"), argThat(items -> items.size() == 1
                && cn.equals(items.getFirst().workerSelector())));
        verify(matching,never()).take(anyString(),anyMap());

    }
    @Test
    void invalidJavaInputsIncludingOverwrittenDuplicatesNeverReachAnOwner() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), org.mockito.Mockito.mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class));
        var valid = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, new WorkerQuery("worker.default", Map.of()));
        var invalid = List.of(
                new TaskItemRequest(" ", "event", Map.of(), 5, 1000L, new WorkerQuery("worker.default", Map.of())),
                new TaskItemRequest("id", " ", Map.of(), 5, 1000L, new WorkerQuery("worker.default", Map.of())),
                new TaskItemRequest("id", "event", null, 5, 1000L, new WorkerQuery("worker.default", Map.of())),
                new TaskItemRequest("id", "event", Map.of(), 11, 1000L, new WorkerQuery("worker.default", Map.of())),
                new TaskItemRequest("id", "event", Map.of(), 5, 0L, new WorkerQuery("worker.default", Map.of())),
                new TaskItemRequest("id", "event", Map.of(), 5, 1000L, null));
        assertThatThrownBy(() -> new WorkerQuery("worker.default", new Object()))
                .isInstanceOf(IllegalArgumentException.class);
        for (var item : invalid) {
            assertThatThrownBy(() -> service.submit("task", List.of(item, valid)))
                    .isInstanceOf(ServerException.class);
        }
        assertThatThrownBy(() -> service.submit("task", null)).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.submit("task", List.of())).isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.submit("task", Collections.nCopies(101, valid)))
                .isInstanceOf(ServerException.class);
        assertThatThrownBy(() -> service.submit(" ", List.of(valid))).isInstanceOf(ServerException.class);
        verifyNoInteractions(submission, catalog);
    }

    @Test
    void unsupportedRuleParametersAreRejectedByMatchingEvenWhenOverwritten() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var matching = mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(matching.normalizeQuery(eq("group"),any())).thenAnswer(call -> call.getArgument(1));

        var descriptor = new TaskRuntime.TaskDescriptor("task", "group", TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "3"), "worker.default", java.util.List.of(new com.xa.mass.kernel.assignment.RefillTarget(java.util.Map.of(), 100)));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor));
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), matching);
        var any = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, new WorkerQuery("worker.default", Map.of()));
        for (Map<String, ?> expression : List.of(
                Map.of("worker.test.region", List.of("east", "west")),
                Map.of("country", List.of("CN", "US")))) {
            var selector = new WorkerQuery("worker.default", expression);
            doThrow(new IllegalArgumentException("unsupported")).when(matching).normalizeQuery("group",selector);
            var rejected = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, new WorkerQuery("worker.default", new HashMap<>(expression)));
            assertThatThrownBy(() -> service.submit("task", List.of(rejected, any))).isInstanceOf(ServerException.class);
            verify(matching).normalizeQuery("group",selector);
        }
        verifyNoInteractions(submission);
        verify(matching,never()).take(anyString(),anyMap());

    }

    @Test
    void invalidJavaResultQueriesNeverReachAnOwner() {
        var runtime = mock(TaskRuntime.class);
        var catalog = mock(TaskResourceCatalog.class);
        var service = new TaskDataService(runtime, catalog, new TaskItemMapper(),
                mock(TaskItemScoreBandCore.class), new TaskItemOutcomeProperties(Map.of()), mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class));
        var cases = new ArrayList<List<String>>();
        cases.add(null);
        cases.add(List.of());
        cases.add(Collections.singletonList(null));
        cases.add(List.of(" "));
        cases.add(Collections.nCopies(1001, "id"));
        for (var ids : cases) {
            assertThatThrownBy(() -> service.loadTaskItemResults("task", ids)).isInstanceOf(ServerException.class);
        }
        assertThatThrownBy(() -> service.loadTaskItemResults(null, List.of("id")))
                .isInstanceOf(ServerException.class);
        verifyNoInteractions(runtime, catalog);
    }
}
