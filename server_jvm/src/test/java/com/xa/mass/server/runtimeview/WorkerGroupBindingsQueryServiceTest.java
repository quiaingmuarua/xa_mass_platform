package com.xa.mass.server.runtimeview;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties;
import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties.*;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.server.project.*;
import com.xa.mass.workermatching.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerGroupBindingsQueryServiceTest {
    private final WorkerResourceCatalog workers = mock(WorkerResourceCatalog.class);
    private final WorkerMatchingCatalog matching = mock(WorkerMatchingCatalog.class);
    private final WorkerGroupBindings bindings = new WorkerGroupBindings(Map.of("c", new MatchingGroup(Set.of("any"), Set.of("worker.any"))),
            new FixedWindowBindingsProperties(Map.of("window", new Pool("window.take", "at", "count", 100, 2, new Feedback(List.of("check")))),
                    Map.of("a", new Group(Map.of("window", new Limits(null, null))))), List.of());
    private final ProjectDirectory projects = new ProjectDirectory(new ProjectAssemblyProperties(List.of(
            new ProjectDefinition("default", List.of(), null), new ProjectDefinition("p", List.of("a", "b"), null))),
            List.of(new ProjectDefinition("module", List.of("a"), "scenario")));
    private final WorkerGroupBindingsQueryService service = new WorkerGroupBindingsQueryService(workers, matching, bindings, projects);

    @Test void directoryIsOnlyTheSortedStartupUnionWithoutOwnerReads() {
        assertThat(service.directory().workerGroupIds()).containsExactly("a", "b", "c");
        assertThat(service.directory().readAt()).isNotNull();
        verifyNoInteractions(workers, matching);
    }
    @Test void pointReadSeparatesProjectScopeFromEnabledResources() {
        when(workers.getWorkerGroupDescriptors(List.of("a"))).thenReturn(Map.of("a",
                new WorkerResourceCatalog.WorkerGroupDescriptor("a", Map.of(), Set.of("check", "extra"))));
        when(matching.capabilities("a")).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of("window"),
                List.of(new WorkerMatchingCatalog.FunctionCapability("window.take", "window"))));
        var view = service.load("a", "read");
        assertThat(view.workerGroup().eventCodes()).containsExactly("check", "extra");
        assertThat(view.fixedWindowPools().getFirst().windowMillis()).isEqualTo(100);
        assertThat(view.fixedWindowPools().getFirst().feedback().messageEvents()).containsExactly("check");
        assertThat(view.projects()).extracting(p -> p.projectId()).containsExactly("default", "module", "p");
        verify(workers).getWorkerGroupDescriptors(List.of("a")); verify(matching).capabilities("a");
        verifyNoMoreInteractions(workers, matching);
    }
    @Test void missingDescriptorKeepsDeclaredScopeButNotDefaultAndFailureIsNotEmptySuccess() {
        when(workers.getWorkerGroupDescriptors(List.of("b"))).thenReturn(Map.of());
        when(matching.capabilities("b")).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of(), List.of()));
        var missing = service.load("b", "missing");
        assertThat(missing.workerGroup()).isNull(); assertThat(missing.fixedWindowPools()).isEmpty();
        assertThat(missing.projects()).extracting(p -> p.projectId()).containsExactly("p");
        var failure = new IllegalStateException("Owner unavailable");
        when(workers.getWorkerGroupDescriptors(List.of("b"))).thenThrow(failure);
        assertThatThrownBy(() -> service.load("b", "failed")).isInstanceOf(ServerException.class).hasCause(failure);
        assertThatThrownBy(() -> service.load(" ", "invalid")).isInstanceOf(ServerException.class);
    }
    @Test void aKnownRuntimeRegisteredGroupOutsideTheStartupDirectoryCanBeRead() {
        when(workers.getWorkerGroupDescriptors(List.of("external"))).thenReturn(Map.of("external",
                new WorkerResourceCatalog.WorkerGroupDescriptor("external", Map.of(), Set.of("event"))));
        when(matching.capabilities("external")).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of(), List.of()));
        var view = service.load("external", "known");
        assertThat(view.workerGroup()).isNotNull(); assertThat(view.fixedWindowPools()).isEmpty();
        assertThat(view.projects()).extracting(p -> p.projectId()).containsExactly("default");
        assertThat(service.directory().workerGroupIds()).doesNotContain("external");
    }
}
