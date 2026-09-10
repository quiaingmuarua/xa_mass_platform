package com.xa.mass.server.task.call;

import com.xa.mass.server.task.call.TaskRpcStageEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.xa.mass.kernel.task.TaskRuntime;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskRpcResultProbeTest {
    @TempDir Path temporary;

    @Test void nullOwnerProjectionEntriesAreMissesWithoutChangingProbeCompletion() throws Exception {
        var runtime = mock(TaskRuntime.class);
        var registry = mock(TaskRpcWaitRegistry.class);
        var success = TaskRuntime.TaskItemResult.succeeded("opaque");
        var results = new LinkedHashMap<String, TaskRuntime.TaskItemResult>();
        results.put("hit", success);
        results.put("miss", null);
        when(runtime.loadTaskItemResults("task", List.of("hit", "miss"))).thenReturn(results);
        var probe = new TaskRpcResultProbe(runtime, registry, new TaskRpcProperties(1000, 1000, 10, 10, 10, 50, 100, 250));
        Path path = temporary.resolve("probe.jfr");
        try (var recording = new Recording()) {
            recording.enable(TaskRpcStageEvent.class);
            recording.start();
            probe.probe(List.of(new TaskRpcWaitRegistry.ProbeRequest("task", "hit"),
                    new TaskRpcWaitRegistry.ProbeRequest("task", "miss")));
            recording.stop();
            recording.dump(path);
        }
        verify(registry).completeResult("task", "hit", success);
        verify(registry).finishProbe("task", "hit", 0);
        verify(registry).finishProbe("task", "miss", 0);
        verifyNoMoreInteractions(registry);
        var events = RecordingFile.readAllEvents(path);
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getInt("batchSize")).isEqualTo(2);
        assertThat(events.getFirst().getInt("count")).isEqualTo(1);
    }
}
