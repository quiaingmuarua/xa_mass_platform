package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService;
import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlatformPropertiesHandlerTest {
    final WorkerProperties properties = mock(WorkerProperties.class);
    final WorkerSchedulingService scheduling = mock(WorkerSchedulingService.class);
    final WorkerResourceCommandService commands = new WorkerResourceCommandService(properties, scheduling);
    final AtomicBoolean running = new AtomicBoolean(true);
    final AtomicLong failures = new AtomicLong();

    @Test void allProjectionsShareOneReadAndWriteWithDeletionAndLocalPatchVisibility() {
        when(properties.loadWorkerFacts("g", List.of("w")))
                .thenReturn(Map.of("w", new WorkerFacts("w", "g", Map.of(), Map.of("keep", 7, "remove", 1))));
        when(properties.patchWorkerPlatformProperties(eq("g"), eq("w"), anyMap()))
                .thenReturn(new WorkerProperties.MutationResult(WorkerProperties.MutationStatus.APPLIED));
        var calls = new ArrayList<String>();
        var a = new WorkerPropertyProjection("g", "a", "worker.assigned", (current, times) -> {
            assertThat(current).containsEntry("keep", 7).containsEntry("remove", 1);
            calls.add("a:" + times);
            var patch = new LinkedHashMap<String, Object>(); patch.put("remove", null); patch.put("count", times.size());
            return patch;
        });
        var b = new WorkerPropertyProjection("g", "b", "worker.assigned", (current, times) -> {
            assertThat(current).containsEntry("keep", 7).containsEntry("count", 2).doesNotContainKey("remove");
            calls.add("b:" + times); return Map.of("count", 3);
        });
        handler(List.of(b, a)).handle(List.of(notice("a", 10), notice("b", 20), notice("a", 30)));
        var patch = new LinkedHashMap<String, Object>(); patch.put("remove", null); patch.put("count", 3);
        assertThat(calls).containsExactly("a:[10, 30]", "b:[20]");
        verify(properties).loadWorkerFacts("g", List.of("w"));
        verify(properties).patchWorkerPlatformProperties("g", "w", patch);
        verifyNoMoreInteractions(properties);
        verify(scheduling).invalidateCandidates("g", List.of("w"));
        assertThat(failures.get()).isZero();
    }

    @Test void stoppingDuringProjectionPreventsTheWriteAndNextWorker() {
        when(properties.loadWorkerFacts("g", List.of("w", "next")))
                .thenReturn(Map.of("w", new WorkerFacts("w", "g", Map.of(), Map.of()),
                        "next", new WorkerFacts("next", "g", Map.of(), Map.of())));
        var computed = new AtomicLong();
        var projection = new WorkerPropertyProjection("g", "a", "worker.assigned", (current, times) -> {
            computed.incrementAndGet(); running.set(false); return Map.of("count", 1);
        });
        handler(List.of(projection)).handle(List.of(new WorkerObservation("g", List.of("w", "next"),
                10, "a", "worker.assigned")));
        assertThat(computed.get()).isEqualTo(1);
        verify(properties).loadWorkerFacts("g", List.of("w", "next")); verifyNoMoreInteractions(properties);
        verifyNoInteractions(scheduling);
    }

    @Test void aProjectionFailureAbandonsThatWorkersWholePatchButDoesNotStopOtherWorkers() {
        when(properties.loadWorkerFacts("g", List.of("w", "next")))
                .thenReturn(Map.of("w", new WorkerFacts("w", "g", Map.of(), Map.of("invalid", true)),
                        "next", new WorkerFacts("next", "g", Map.of(), Map.of())));
        when(properties.patchWorkerPlatformProperties("g", "next", Map.of("a", 1, "b", 2)))
                .thenReturn(new WorkerProperties.MutationResult(WorkerProperties.MutationStatus.UNCHANGED));
        var a = new WorkerPropertyProjection("g", "a", "worker.assigned", (current, times) -> Map.of("a", 1));
        var b = new WorkerPropertyProjection("g", "b", "worker.assigned", (current, times) -> {
            if (current.containsKey("invalid")) throw new IllegalStateException("invalid properties");
            return Map.of("b", 2);
        });
        handler(List.of(a, b)).handle(List.of(
                new WorkerObservation("g", List.of("w", "next"), 10, "a", "worker.assigned"),
                new WorkerObservation("g", List.of("w", "next"), 20, "b", "worker.assigned")));
        verify(properties, never()).patchWorkerPlatformProperties(eq("g"), eq("w"), anyMap());
        verify(properties).patchWorkerPlatformProperties("g", "next", Map.of("a", 1, "b", 2));
        assertThat(failures.get()).isEqualTo(1);
        verifyNoInteractions(scheduling);
    }

    @Test void theHandlerOnlyReadsLifecycleAndHasNoIndependentRuntimeResources() {
        running.set(false);
        var projection = new WorkerPropertyProjection("g", "a", "worker.assigned", (current, times) -> Map.of());
        handler(List.of(projection)).handle(List.of(notice("a", 10)));
        verifyNoInteractions(properties, scheduling);
        assertThat(running.get()).isFalse();
    }

    PlatformPropertiesHandler handler(List<WorkerPropertyProjection> projections) {
        return new PlatformPropertiesHandler(projections, properties, commands, running::get, failures::addAndGet);
    }

    static WorkerObservation notice(String event, long time) {
        return new WorkerObservation("g", List.of("w"), time, event, "worker.assigned");
    }
}
