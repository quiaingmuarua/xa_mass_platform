package com.xa.mass.server.project;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectWorkerGroupQueryServiceTest {
    @Test void knownBindingsAreBatchedAndMissingDescriptorsStayVisible() {
        var ids = IntStream.range(0, 201).mapToObj(i -> "g" + i).toList();
        var projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(new ProjectDefinition("p", ids))), List.of());
        var workers = mock(WorkerResourceCatalog.class);
        var matching = mock(WorkerMatchingCatalog.class);
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of("g0",
                new WorkerGroupDescriptor("g0", Map.of(), Set.of("event"))));
        when(matching.capabilities(anyString())).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of("any"), Set.of("workerId", "worker.any")));
        var service = new ProjectWorkerGroupQueryService(projects, workers, matching);
        var rows = service.list("p");
        assertThat(rows).hasSize(201);
        assertThat(rows.getFirst().workerGroup().eventCodes()).containsExactly("event");
        assertThat(rows.get(1).workerGroup()).isNull();
        assertThat(rows.get(1).matching().functions()).contains("workerId");
        verify(workers).getWorkerGroupDescriptors(ids.subList(0, 100));
        verify(workers).getWorkerGroupDescriptors(ids.subList(100, 200));
        verify(workers).getWorkerGroupDescriptors(ids.subList(200, 201));
        verifyNoMoreInteractions(workers);
        assertThatThrownBy(() -> service.list("unknown")).isInstanceOf(ServerException.class);
        when(workers.getWorkerGroupDescriptors(anyList())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.list("p")).isInstanceOf(ServerException.class).hasCauseInstanceOf(IllegalStateException.class);
    }
}
