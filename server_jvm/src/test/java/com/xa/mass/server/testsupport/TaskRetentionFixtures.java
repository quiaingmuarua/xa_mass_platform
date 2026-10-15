package com.xa.mass.server.testsupport;

import com.xa.mass.kernel.task.TaskResourceCatalog;
import com.xa.mass.kernel.task.TaskRuntime;
import com.xa.mass.kernel.score.TaskScoreBandCore;
import com.xa.mass.server.operation.OperationGuard;
import com.xa.mass.server.task.directory.TaskCreationRegistry;
import com.xa.mass.server.task.result.TaskResultsExportService;
import com.xa.mass.server.task.retention.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import static org.mockito.Mockito.*;

public final class TaskRetentionFixtures {
    private TaskRetentionFixtures() { }
    public static TaskRetentionProperties properties() {
        return new TaskRetentionProperties(false, Duration.ofHours(1), Duration.ofDays(7), Duration.ofHours(1), Path.of("build/test-archives"));
    }
    public static TaskRuntime openRuntime() { return openData(mock(TaskRuntime.class)); }
    public static TaskRuntime openData(TaskRuntime runtime) {
        when(runtime.loadTaskDataStates(anyList())).thenAnswer(call -> {
            List<String> ids = call.getArgument(0);
            var states = new LinkedHashMap<String, TaskRuntime.TaskDataState>();
            ids.forEach(id -> states.put(id, new TaskRuntime.TaskDataState(TaskRuntime.TaskDataPhase.OPEN, null)));
            return states;
        });
        return runtime;
    }
    public static TaskResultsExportService results(TaskRuntime runtime, TaskResourceCatalog catalog, TaskScoreBandCore scores, OperationGuard operations) {
        return new TaskResultsExportService(catalog, scores, runtime, tools.jackson.databind.json.JsonMapper.builder().build(), operations, mock(TaskResultArchive.class));
    }
    public static TaskCreationRegistry creations() {
        var registry = mock(TaskCreationRegistry.class);
        var claims = new HashMap<List<String>, TaskCreationRegistry.Claim>();
        var fingerprints = new HashMap<List<String>, String>();
        when(registry.reserve(anyString(), anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            var key = List.of((String) call.getArgument(0), (String) call.getArgument(1), (String) call.getArgument(2));
            String fingerprint = call.getArgument(3) + ":" + call.getArgument(4);
            if (fingerprints.containsKey(key) && !fingerprints.get(key).equals(fingerprint))
                throw new com.xa.mass.server.error.ServerException(com.xa.mass.server.error.ServerErrorCode.TASK_STATE_CONFLICT, "fixture.reserve", "Creation request has different content", null);
            fingerprints.put(key, fingerprint);
            return claims.computeIfAbsent(key, ignored -> new TaskCreationRegistry.Claim(UUID.randomUUID().toString().replace("-", "").repeat(2), "task-" + UUID.randomUUID().toString().replace("-", "")));
        });
        when(registry.isCurrent(any())).thenAnswer(call -> claims.containsValue(call.getArgument(0)));
        return registry;
    }
}
