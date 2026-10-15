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
    @Test void defaultTraversesEveryPageDeduplicatesAndRefreshesWithoutSampling() {
        var projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(new ProjectDefinition("default", List.of()))), List.of());
        var workers = mock(WorkerResourceCatalog.class);
        var matching = mock(WorkerMatchingCatalog.class);
        var rows = new java.util.LinkedHashMap<String, WorkerGroupDescriptor>();
        IntStream.range(0, 201).forEach(i -> rows.put("g" + i, new WorkerGroupDescriptor("g" + i, Map.of(), Set.of("event"))));
        when(workers.scanWorkerGroupDescriptors("0", 100)).thenReturn(new WorkerResourceCatalog.WorkerGroupPage("17", rows));
        when(workers.scanWorkerGroupDescriptors("17", 100)).thenReturn(new WorkerResourceCatalog.WorkerGroupPage("0", Map.of("g0", rows.get("g0"))));
        when(matching.capabilities(anyString())).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of(),
                List.of(new WorkerMatchingCatalog.FunctionCapability("workerId", null))));
        var service = new ProjectWorkerGroupQueryService(projects, workers, matching);
        assertThat(service.list("default")).hasSize(201).extracting(ProjectWorkerGroupQueryService.ProjectWorkerGroup::workerGroupId).isSorted();
        var later = new WorkerGroupDescriptor("later", Map.of(), Set.of("new-event"));
        when(workers.scanWorkerGroupDescriptors("17", 100)).thenReturn(new WorkerResourceCatalog.WorkerGroupPage("0", Map.of("later", later)));
        assertThat(service.list("default")).hasSize(202).extracting(ProjectWorkerGroupQueryService.ProjectWorkerGroup::workerGroupId).contains("later");
        verify(workers, never()).sampleWorkerGroupDescriptors(anyInt());
        verify(workers, never()).getWorkerGroupDescriptors(anyList());
        when(workers.scanWorkerGroupDescriptors("17", 100)).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.list("default")).isInstanceOf(ServerException.class).hasCauseInstanceOf(IllegalStateException.class);
        doReturn(new WorkerResourceCatalog.WorkerGroupPage("17", Map.of())).when(workers).scanWorkerGroupDescriptors("17", 100);
        assertThatThrownBy(() -> service.list("default")).isInstanceOf(ServerException.class).hasRootCauseMessage("Group directory cursor repeated");
    }
    @Test void knownBindingsAreBatchedAndMissingDescriptorsStayVisible() {
        var ids = IntStream.range(0, 201).mapToObj(i -> "g" + i).toList();
        var projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(new ProjectDefinition("p", ids))), List.of());
        var workers = mock(WorkerResourceCatalog.class);
        var matching = mock(WorkerMatchingCatalog.class);
        when(workers.getWorkerGroupDescriptors(anyList())).thenReturn(Map.of("g0",
                new WorkerGroupDescriptor("g0", Map.of(), Set.of("event"))));
        when(matching.capabilities(anyString())).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of("any"), List.of(new WorkerMatchingCatalog.FunctionCapability("workerId", null), new WorkerMatchingCatalog.FunctionCapability("worker.any", "any"))));
        var service = new ProjectWorkerGroupQueryService(projects, workers, matching);
        var rows = service.list("p");
        assertThat(rows).hasSize(201);
        assertThat(rows.getFirst().workerGroup().eventCodes()).containsExactly("event");
        assertThat(rows.get(1).workerGroup()).isNull();
        assertThat(rows.get(1).matching().functions()).extracting(WorkerMatchingCatalog.FunctionCapability::name).contains("workerId");
        verify(workers).getWorkerGroupDescriptors(ids.subList(0, 100));
        verify(workers).getWorkerGroupDescriptors(ids.subList(100, 200));
        verify(workers).getWorkerGroupDescriptors(ids.subList(200, 201));
        verifyNoMoreInteractions(workers);
        assertThatThrownBy(() -> service.list("unknown")).isInstanceOf(ServerException.class);
        when(workers.getWorkerGroupDescriptors(anyList())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> service.list("p")).isInstanceOf(ServerException.class).hasCauseInstanceOf(IllegalStateException.class);
    }
}
