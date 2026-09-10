package com.xa.mass.server.task.call;

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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TaskCallSubmissionServiceTest {
    @Test
    void invalidJavaInputsIncludingOverwrittenDuplicatesNeverReachAnOwner() {
        var submission = mock(TaskCallItemSubmission.class);
        var catalog = mock(TaskResourceCatalog.class);
        var service = new TaskCallSubmissionService(submission, catalog, new TaskItemMapper());
        var valid = new TaskItemRequest("id", "event", Map.of(), 5, 1000L, List.of());
        var invalid = List.of(
                new TaskItemRequest(" ", "event", Map.of(), 5, 1000L, List.of()),
                new TaskItemRequest("id", " ", Map.of(), 5, 1000L, List.of()),
                new TaskItemRequest("id", "event", null, 5, 1000L, List.of()),
                new TaskItemRequest("id", "event", Map.of(), 11, 1000L, List.of()),
                new TaskItemRequest("id", "event", Map.of(), 5, 0L, List.of()),
                new TaskItemRequest("id", "event", Map.of(), 5, 1000L, null),
                new TaskItemRequest("id", "event", Map.of(), 5, 1000L, List.of("country", "$eq", "CN")));
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
    void invalidJavaResultQueriesNeverReachAnOwner() {
        var runtime = mock(TaskRuntime.class);
        var catalog = mock(TaskResourceCatalog.class);
        var service = new TaskDataService(runtime, catalog, new TaskItemMapper(),
                mock(TaskItemScoreBandCore.class), new TaskItemOutcomeProperties(Map.of()));
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
