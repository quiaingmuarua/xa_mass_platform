package com.xa.mass.server.task.call;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.TaskRuleBinding;
import com.xa.mass.kernel.assignment.EligibilityQuery;

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
    @Test void bindingGroupMismatchRejectsCallBeforeNormalizationOrSubmission() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var matching = mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of(
                "task", new TaskRuntime.TaskDescriptor("task", "group",
                        TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE,
                        Map.of("priority", "0", "maxRetryTimes", "3"))));
        when(matching.loadTaskBindings(List.of("task"))).thenReturn(Map.of("task",
                new TaskRuleBinding("worker.default", "other-group",
                        List.of(new RefillTarget(Map.of(), 100)))));
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), matching);
        var item = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, EligibilityQuery.parse(Map.of()));
        assertThatThrownBy(() -> service.submit("task", List.of(item))).isInstanceOf(ServerException.class);
        verifyNoInteractions(submission);
        verify(matching).loadTaskBindings(List.of("task"));
        verifyNoMoreInteractions(matching);
    }

    @Test void matchingAdmissionValidatesOverwrittenInputsAndPersistsTheSelectorWithoutSelection() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var matching = mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(matching.normalizeQuery(eq("group"),eq("worker.default"),any())).thenAnswer(call -> call.getArgument(2));
        when(matching.loadTaskBindings(List.of("task"))).thenReturn(Map.of("task",new TaskRuleBinding("worker.default","group",List.of(new RefillTarget(Map.of(),100)))));
        var descriptor = new TaskRuntime.TaskDescriptor("task", "group", TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "3"));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor));
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), matching);
        var cn = EligibilityQuery.parse(Map.of("worker.country", List.of("CN")));
        var malformed = EligibilityQuery.parse(Map.of("worker.country", List.of("cn")));
        doThrow(new IllegalArgumentException("invalid country")).when(matching).normalizeQuery("group","worker.default",malformed);
        var bad = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, EligibilityQuery.parse(Map.of("worker.country", List.of("cn"))));
        var good = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, EligibilityQuery.parse(Map.of("worker.country", List.of("CN"))));
        assertThatThrownBy(() -> service.submit("task", List.of(bad, good))).isInstanceOf(ServerException.class);
        verifyNoInteractions(submission);
        doThrow(new IllegalArgumentException("index disabled")).when(matching).normalizeQuery("group","worker.default",cn);
        assertThatThrownBy(() -> service.submit("task", List.of(good))).isInstanceOf(ServerException.class);
        verifyNoInteractions(submission);
        doReturn(cn).when(matching).normalizeQuery("group","worker.default",cn);
        when(submission.submit(eq("task"), anyList())).thenReturn(new TaskCallItemSubmission.TaskCallSubmissionResult(
                TaskCallItemSubmission.TaskCallSubmissionStatus.SUBMITTED,
                Map.of("id", new TaskRuntime.TaskItemAppendResult(TaskRuntime.TaskItemAppendStatus.APPENDED)), null));
        org.assertj.core.api.Assertions.assertThat(service.submit("task", List.of(good))).containsExactly("id");
        verify(submission).submit(eq("task"), argThat(items -> items.size() == 1
                && cn.equals(items.getFirst().workerSelector())));
        verify(matching,never()).take(anyString(),anyString(),anyMap());

    }
    @Test
    void invalidJavaInputsIncludingOverwrittenDuplicatesNeverReachAnOwner() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), org.mockito.Mockito.mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class));
        var valid = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, EligibilityQuery.parse(Map.of()));
        var invalid = List.of(
                new TaskItemRequest(" ", "event", Map.of(), 5, 1000L, EligibilityQuery.parse(Map.of())),
                new TaskItemRequest("id", " ", Map.of(), 5, 1000L, EligibilityQuery.parse(Map.of())),
                new TaskItemRequest("id", "event", null, 5, 1000L, EligibilityQuery.parse(Map.of())),
                new TaskItemRequest("id", "event", Map.of(), 11, 1000L, EligibilityQuery.parse(Map.of())),
                new TaskItemRequest("id", "event", Map.of(), 5, 0L, EligibilityQuery.parse(Map.of())),
                new TaskItemRequest("id", "event", Map.of(), 5, 1000L, null));
        assertThatThrownBy(() -> EligibilityQuery.parse(Map.of("country", List.of(123))))
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
    void unsupportedBindingAndParametersAreRejectedByMatchingEvenWhenOverwritten() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var matching = mock(com.xa.mass.workermatching.WorkerMatchingCatalog.class);
        when(matching.normalizeQuery(eq("group"),eq("worker.default"),any())).thenAnswer(call -> call.getArgument(2));
        when(matching.loadTaskBindings(List.of("task"))).thenReturn(Map.of("task",new TaskRuleBinding("worker.default","group",List.of(new RefillTarget(Map.of(),100)))));
        var descriptor = new TaskRuntime.TaskDescriptor("task", "group", TaskRuntime.TaskIdleDisposition.PARK_WHEN_IDLE, Map.of("priority", "0", "maxRetryTimes", "3"));
        when(catalog.loadTaskAllocationDescriptors(List.of("task"))).thenReturn(Map.of("task", descriptor));
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper(), matching);
        var any = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, EligibilityQuery.parse(Map.of()));
        for (Map<String, ?> expression : List.of(
                Map.of("worker.test.region", List.of("east", "west")),
                Map.of("worker.country", List.of("CN", "US")))) {
            var selector = EligibilityQuery.parse(expression);
            doThrow(new IllegalArgumentException("unsupported")).when(matching).normalizeQuery("group","worker.default",selector);
            var rejected = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, EligibilityQuery.parse(new HashMap<>(expression)));
            assertThatThrownBy(() -> service.submit("task", List.of(rejected, any))).isInstanceOf(ServerException.class);
            verify(matching).normalizeQuery("group","worker.default",selector);
        }
        verifyNoInteractions(submission);
        verify(matching,never()).take(anyString(),anyString(),anyMap());

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
