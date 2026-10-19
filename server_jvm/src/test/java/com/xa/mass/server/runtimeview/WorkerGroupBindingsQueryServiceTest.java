package com.xa.mass.server.runtimeview;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.server.assembly.runtime.FixedWindowBindingsProperties;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.observation.WorkerGroupBindings;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerGroupBindingsQueryServiceTest {
    private final WorkerResourceCatalog workers = mock(WorkerResourceCatalog.class);
    private final WorkerMatchingCatalog matching = mock(WorkerMatchingCatalog.class);
    private final WorkerGroupBindings bindings = new WorkerGroupBindings(Map.of(), new FixedWindowBindingsProperties(
            Map.of("window", new FixedWindowBindingsProperties.Pool("window.take", "at", "count", 100, 2)),
            Map.of("observe", new FixedWindowBindingsProperties.Feedback("window", List.of("check"))),
            Map.of("g", new FixedWindowBindingsProperties.Group(Map.of("window", new FixedWindowBindingsProperties.Limits(null, null)), List.of("observe"))), Map.of()), List.of(),
            new com.xa.mass.server.project.ProjectDirectory(new com.xa.mass.server.project.ProjectAssemblyProperties(List.of()), List.of()));
    private final WorkerGroupBindingsQueryService service = new WorkerGroupBindingsQueryService(workers, matching, bindings);

    @Test void pointReadUsesOnlyRegisteredDescriptorAndStartupCapabilities() {
        when(workers.getWorkerGroupDescriptors(List.of("g"))).thenReturn(Map.of("g",
                new WorkerResourceCatalog.WorkerGroupDescriptor("g", Map.of(), Set.of("check", "extra"))));
        when(matching.capabilities("g")).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of("window"),
                List.of(new WorkerMatchingCatalog.FunctionCapability("window.take", "window"))));
        var view = service.load("g", "read");
        assertThat(view.workerGroup().eventCodes()).containsExactly("check", "extra");
        assertThat(view.fixedWindowPools().getFirst().windowMillis()).isEqualTo(100);
        assertThat(view.feedbacks().getFirst().messageEvents()).containsExactly("check");
        assertThat(view.readAt()).isNotNull();
        verify(workers).getWorkerGroupDescriptors(List.of("g"));
        verify(matching).capabilities("g");
        verifyNoMoreInteractions(workers, matching);
    }

    @Test void missingDescriptorIsExplicitAndFailureIsNotEmptySuccess() {
        when(workers.getWorkerGroupDescriptors(List.of("g"))).thenReturn(Map.of());
        when(matching.capabilities("g")).thenReturn(new WorkerMatchingCatalog.Capabilities(Set.of(), List.of()));
        assertThat(service.load("g", "missing").workerGroup()).isNull();
        var failure = new IllegalStateException("Owner unavailable");
        when(workers.getWorkerGroupDescriptors(List.of("g"))).thenThrow(failure);
        assertThatThrownBy(() -> service.load("g", "failed")).isInstanceOf(ServerException.class).hasCause(failure);
        assertThatThrownBy(() -> service.load(" ", "invalid")).isInstanceOf(ServerException.class);
    }
}
