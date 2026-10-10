package com.xa.mass.server.task.observation;

import com.xa.mass.server.project.ProjectDirectory;
import com.xa.mass.server.task.TaskDataService;
import com.xa.mass.server.api.v1.contract.task.TaskItemResultResponse;
import com.xa.mass.workermatching.MatchingComposition;
import com.xa.mass.workermatching.PlatformLeaseState;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskLeaseProjectionServiceTest {
    final ProjectDirectory projects = mock(ProjectDirectory.class);
    final TaskDataService results = mock(TaskDataService.class);
    final MatchingComposition matching = mock(MatchingComposition.class);
    final PlatformLeaseState leases = mock(PlatformLeaseState.class);
    final Coordinate coordinate = new Coordinate("number", "A", "w");
    TaskLeaseProjectionService service() {
        when(projects.requireManagedTaskId("sms", "g")).thenReturn("task");
        when(matching.platformLeases()).thenReturn(leases);
        when(matching.leaseMetrics()).thenReturn(Map.of());
        return new TaskLeaseProjectionService(List.of(new TaskLeaseProjection("sms", "g", "pool",
                rows -> rows.isEmpty() ? Map.of() : Map.of(coordinate, 2000L))), projects, results, matching);
    }
    @Test void onlyDeclaredTasksReadStoredResultsAndWriteTheirAbsoluteDeadline() throws Exception {
        var service = service(); var written = new CountDownLatch(1);
        when(results.loadTaskItemResults("task", List.of("m"))).thenReturn(Map.of("m", TaskItemResultResponse.succeeded("snapshot")));
        doAnswer(call -> { written.countDown(); return null; }).when(leases).record("g", "pool", Map.of(coordinate, 2000L));
        service.start();
        try {
            service.accept("unrelated", List.of("m")); service.accept("task", List.of("m"));
            assertThat(written.await(2, TimeUnit.SECONDS)).isTrue();
            verify(results).loadTaskItemResults("task", List.of("m"));
            verifyNoMoreInteractions(results);
        } finally { service.stop(); }
    }
    @Test void capacityAndShutdownDropHintsWithoutBlockingTheProducer() throws Exception {
        var service = service(); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(results.loadTaskItemResults(anyString(), anyList())).thenAnswer(call -> {
            entered.countDown(); release.await(5, TimeUnit.SECONDS); return Map.of();
        });
        service.start();
        try {
            service.accept("task", List.of("first")); assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 300; i++) service.accept("task", List.of("m" + i));
            assertThat(((Number) service.metrics().get("dropped")).longValue()).isGreaterThan(0);
            service.stop();
            assertThat(service.isRunning()).isFalse();
            verifyNoInteractions(leases);
        } finally { release.countDown(); service.stop(); }
    }
    @Test void projectionFailuresDoNotStopLaterNotices() throws Exception {
        var service = service(); var written = new CountDownLatch(2);
        when(results.loadTaskItemResults(anyString(), anyList())).thenReturn(Map.of("m", TaskItemResultResponse.succeeded("snapshot")));
        doAnswer(call -> { written.countDown(); throw new IllegalStateException("Redis unavailable"); })
                .when(leases).record(anyString(), anyString(), anyMap());
        service.start();
        try {
            service.accept("task", List.of("m"));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (written.getCount() == 2 && System.nanoTime() < end) Thread.sleep(5);
            service.accept("task", List.of("m2"));
            assertThat(written.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.isRunning()).isTrue();
        } finally { service.stop(); }
    }
}
