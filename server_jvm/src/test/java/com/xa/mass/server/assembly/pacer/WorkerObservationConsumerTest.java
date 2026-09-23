package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import com.xa.mass.server.worker.observation.WorkerPropertyProjection;
import com.xa.mass.server.worker.resource.WorkerResourceCommandService;
import com.xa.mass.server.worker.scheduling.WorkerSchedulingService;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.workermatching.WorkerProperties.WorkerFacts;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.support.GenericApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(20)
class WorkerObservationConsumerTest {
    final WorkerProperties properties = mock(WorkerProperties.class);
    final WorkerSchedulingService scheduling = mock(WorkerSchedulingService.class);
    final WorkerResourceCommandService commands = new WorkerResourceCommandService(properties, scheduling);
    @TempDir Path temporary;

    @Test void unmatchedGroupAndEventsDoNotReadFactsAndAbsentFactsAreNotCreated() throws Exception {
        var read = new CountDownLatch(1);
        when(properties.loadWorkerFacts("g", List.of("absent"))).thenAnswer(call -> { read.countDown(); return Map.of(); });
        var consumer = consumer(projection("g", "event", (current, times) -> Map.of("someOtherField", times.size())));
        consumer.start();
        try {
            consumer.accept(new WorkerObservation("other", List.of("w"), 1, "event", "worker.assigned"));
            consumer.accept(new WorkerObservation("g", List.of("w"), 1, "wrong", "worker.assigned"));
            consumer.accept(new WorkerObservation("g", List.of("w"), 1, "event", "wrong"));
            consumer.accept(observation("absent", 1));
            await(read);
        } finally { consumer.stop(); }
        verify(properties).loadWorkerFacts("g", List.of("absent"));
        verifyNoMoreInteractions(properties);
        verifyNoInteractions(scheduling);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "other,event,worker.assigned", "g,wrong,worker.assigned", "g,event,wrong"
    })
    void unmatchedTrafficCannotFillQueueOrChangeDiagnostics(String group, String message, String point) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var patched = new CountDownLatch(1);
        when(properties.loadWorkerFacts("g", List.of("gate"))).thenAnswer(call -> {
            entered.countDown(); await(release); return Map.of();
        });
        when(properties.loadWorkerFacts("g", List.of("selected")))
                .thenReturn(Map.of("selected", new WorkerFacts("selected", "g", Map.of(), Map.of())));
        when(properties.patchWorkerPlatformProperties("g", "selected", Map.of("counter", 1))).thenAnswer(call -> {
            patched.countDown(); return new WorkerProperties.MutationResult(WorkerProperties.MutationStatus.APPLIED);
        });
        var consumer = consumer(projection("g", "event", (current, times) -> Map.of("counter", times.size())));
        var unmatched = new WorkerObservation(group, List.of("ignored"), 1, message, point);
        try (var recording = new Recording()) {
            recording.enable("xa.mass.WorkerObservation"); recording.start();
            consumer.accept(unmatched);
            consumer.accept(observation("before-start", 0)); // Only this matched notice counts as dropped.
            consumer.start();
            try {
                consumer.accept(observation("gate", 0)); await(entered);
                for (int i = 0; i <= WorkerObservationConsumer.QUEUE_BATCHES; i++) consumer.accept(unmatched);
                consumer.accept(observation("selected", 1));
                release.countDown(); await(patched);
            } finally { release.countDown(); consumer.stop(); }
            recording.stop(); var file = temporary.resolve("unmatched.jfr"); recording.dump(file);
            var events = RecordingFile.readAllEvents(file).stream()
                    .filter(event -> event.getEventType().getName().equals("xa.mass.WorkerObservation")).toList();
            assertThat(events).isNotEmpty();
            var summary = events.getLast();
            assertThat(summary.getLong("enqueuedBatches")).isEqualTo(2);
            assertThat(summary.getLong("droppedBatches")).isEqualTo(1);
            assertThat(summary.getLong("processingFailures")).isZero();
        }
        verify(properties).loadWorkerFacts("g", List.of("gate"));
        verify(properties).loadWorkerFacts("g", List.of("selected"));
        verify(properties).patchWorkerPlatformProperties("g", "selected", Map.of("counter", 1));
        verifyNoMoreInteractions(properties);
        verify(scheduling).invalidateCandidates("g", List.of("selected"));
        verifyNoMoreInteractions(scheduling);
    }

    @Test void aDrainGroupsInterleavedEventsByFirstAppearanceAndPatchesEachWorkerOnce() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var patched = new CountDownLatch(3);
        var calls = new ConcurrentLinkedQueue<String>();
        when(properties.loadWorkerFacts(anyString(), anyList())).thenAnswer(call -> {
            String group = call.getArgument(0); List<String> ids = call.getArgument(1);
            if (ids.contains("gate")) { entered.countDown(); await(release); return Map.of(); }
            var result = new java.util.LinkedHashMap<String, WorkerFacts>();
            ids.forEach(id -> result.put(id, new WorkerFacts(id, group, Map.of(), Map.of("unrelated", 7))));
            return result;
        });
        when(properties.patchWorkerPlatformProperties(anyString(), anyString(), anyMap())).thenAnswer(call -> {
            patched.countDown(); return new WorkerProperties.MutationResult(WorkerProperties.MutationStatus.APPLIED);
        });
        // Register B before A to distinguish declaration order from first notification appearance.
        var consumer = consumer(projection("g", "another", (current, times) -> {
            assertThat(current).containsEntry("counted", 2).containsEntry("overlap", 30L);
            calls.add("B:" + times); return Map.of("anotherField", times.getFirst(), "overlap", times.getFirst());
        }), projection("g", "event", (current, times) -> {
            assertThat(current).containsEntry("unrelated", 7);
            assertThatThrownBy(() -> current.put("bad", true)).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> times.add(0L)).isInstanceOf(UnsupportedOperationException.class);
            calls.add("A:" + times); return Map.of("counted", times.size(), "overlap", times.getLast());
        }), projection("other", "event", (current, times) -> Map.of("groupField", true)));
        consumer.start();
        try {
            consumer.accept(observation("gate", 0)); await(entered);
            consumer.accept(new WorkerObservation("g", List.of("w", "v"), 10, "event", "worker.assigned"));
            consumer.accept(new WorkerObservation("g", List.of("w"), 20, "another", "worker.assigned"));
            consumer.accept(observation("w", 30));
            consumer.accept(observation("v", 5)); consumer.accept(observation("v", 10));
            consumer.accept(new WorkerObservation("other", List.of("w"), 14, "event", "worker.assigned"));
            release.countDown(); await(patched);
        } finally { release.countDown(); consumer.stop(); }
        assertThat(calls).containsExactly("A:[10, 30]", "B:[20]", "A:[10, 5, 10]");
        verify(properties).patchWorkerPlatformProperties("g", "w", Map.of("counted", 2, "anotherField", 20L, "overlap", 20L));
        verify(properties).patchWorkerPlatformProperties("g", "v", Map.of("counted", 3, "overlap", 10L));
        verify(properties).patchWorkerPlatformProperties("other", "w", Map.of("groupField", true));
        verify(properties, times(3)).patchWorkerPlatformProperties(anyString(), anyString(), anyMap());
        verify(scheduling).invalidateCandidates("g", List.of("w"));
        verify(scheduling).invalidateCandidates("g", List.of("v"));
        verify(scheduling).invalidateCandidates("other", List.of("w"));
        verifyNoMoreInteractions(scheduling);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {205, 1000})
    void readComputeAndWriteFailuresAreIsolatedAndThePropertiesReadBudgetIsOwnedByProperties(int size) throws Exception {
        var complete = new CountDownLatch(1);
        var pages = new ArrayList<List<String>>();
        when(properties.loadWorkerFacts(eq("g"), anyList())).thenAnswer(call -> {
            List<String> ids = call.getArgument(1); pages.add(List.copyOf(ids));
            if (ids.contains("w0")) throw new IllegalStateException("read failed");
            var result = new java.util.LinkedHashMap<String, WorkerFacts>();
            for (String id : ids) if (!id.equals("w101")) result.put(id, new WorkerFacts(id, "g", Map.of(), Map.of("id", id)));
            return result;
        });
        when(properties.patchWorkerPlatformProperties(eq("g"), anyString(), anyMap())).thenAnswer(call -> {
            String worker = call.getArgument(1);
            if (worker.equals("w103")) throw new IllegalStateException("write failed");
            if (worker.equals("w" + (size - 1))) complete.countDown();
            return new WorkerProperties.MutationResult(WorkerProperties.MutationStatus.APPLIED);
        });
        var consumer = consumer(projection("g", "event", (current, times) -> {
            if (current.get("id").equals("w102")) throw new IllegalArgumentException("invalid state");
            return Map.of("observed", times.getFirst());
        }));
        consumer.start();
        try {
            consumer.accept(new WorkerObservation("g", IntStream.range(0, size).mapToObj(i -> "w" + i).toList(),
                    123, "event", "worker.assigned"));
            await(complete);
        } finally { consumer.stop(); }
        assertThat(pages).allSatisfy(page -> assertThat(page.size()).isLessThanOrEqualTo(100));
        assertThat(pages.stream().mapToInt(List::size).sum()).isEqualTo(size);
        verify(properties, never()).patchWorkerPlatformProperties(eq("g"), eq("w101"), anyMap());
        verify(properties, never()).patchWorkerPlatformProperties(eq("g"), eq("w102"), anyMap());
        verify(properties).patchWorkerPlatformProperties(eq("g"), eq("w103"), anyMap());
        verify(properties).patchWorkerPlatformProperties("g", "w" + (size - 1), Map.of("observed", 123L));
    }

    @Test void fullQueueDropsWholeBatchesAndCloseDiscardsBacklogWithoutFlush() throws Exception {
        var reading = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(properties.loadWorkerFacts("g", List.of("gate"))).thenAnswer(call -> {
            reading.countDown();
            try { await(release); } catch (InterruptedException stop) { interrupted.countDown(); await(release); }
            return Map.of("gate", new WorkerFacts("gate", "g", Map.of(), Map.of()));
        });
        var consumer = consumer(projection("g", "event", (current, times) -> Map.of("counter", 1)));
        try (var recording = new Recording()) {
            recording.enable("xa.mass.WorkerObservation"); recording.start();
            consumer.start();
            try {
                consumer.accept(observation("gate", 0)); await(reading);
                for (int i = 0; i < WorkerObservationConsumer.QUEUE_BATCHES; i++) consumer.accept(observation("queued", i));
                consumer.accept(new WorkerObservation("g", List.of("drop-a", "drop-b"), 1, "event", "worker.assigned"));
                var closing = CompletableFuture.runAsync(consumer::stop);
                await(interrupted);
                assertThat(closing).isNotDone();
                release.countDown(); closing.get(5, TimeUnit.SECONDS);
                consumer.destroy();
            } finally { release.countDown(); consumer.stop(); }
            recording.stop(); var file = temporary.resolve("observations.jfr"); recording.dump(file);
            var events = RecordingFile.readAllEvents(file).stream()
                    .filter(event -> event.getEventType().getName().equals("xa.mass.WorkerObservation")).toList();
            assertThat(events).isNotEmpty();
            assertThat(events.getLast().getLong("enqueuedBatches")).isEqualTo(257);
            assertThat(events.getLast().getLong("droppedBatches")).isEqualTo(257);
        }
        assertThat(consumer.isRunning()).isFalse();
        verify(properties).loadWorkerFacts("g", List.of("gate")); verifyNoMoreInteractions(properties);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void noConsumersNeedNoResourcesAndLifecycleStopsConsumerBeforeOwnerDestruction(boolean failStartup) {
        var empty = consumer(); empty.start(); assertThat(empty.isRunning()).isFalse(); empty.stop();
        verifyNoInteractions(properties, scheduling);
        var projection = projection("g", "event", (current, times) -> Map.of());
        assertThatThrownBy(() -> consumer(projection, projection)).isInstanceOf(IllegalArgumentException.class);
        var consumer = consumer(projection);
        var events = new ArrayList<String>();
        try (var context = new GenericApplicationContext()) {
            context.registerBean("observations", WorkerObservationConsumer.class, () -> consumer);
            context.registerBean("owner", DisposableBean.class, () -> () -> {
                assertThat(consumer.isRunning()).isFalse(); events.add("owner-closed");
            });
            context.registerBean("pacerFixture", SmartLifecycle.class, () -> new SmartLifecycle() {
                boolean running;
                public void start() { assertThat(consumer.isRunning()).isTrue(); running = true; events.add("pacer-started"); }
                public void stop() { assertThat(consumer.isRunning()).isTrue(); running = false; events.add("pacer-stopped"); }
                public boolean isRunning() { return running; }
                public int getPhase() { return Integer.MIN_VALUE + 1; }
            });
            if (failStartup) {
                context.registerBean("failure", SmartLifecycle.class, () -> new SmartLifecycle() {
                    public void start() { throw new IllegalStateException("initialization failed"); }
                    public void stop() {}
                    public boolean isRunning() { return false; }
                });
                assertThatThrownBy(context::refresh).hasRootCauseMessage("initialization failed");
            } else context.refresh();
        }
        assertThat(consumer.isRunning()).isFalse();
        assertThat(events).containsExactly("pacer-started", "pacer-stopped", "owner-closed");
    }

    WorkerObservationConsumer consumer(WorkerPropertyProjection... projections) {
        var beans = new StaticListableBeanFactory(Map.of("properties", properties, "commands", commands));
        return new KernelPacerConfiguration().workerObservationConsumer(List.of(projections),
                beans.getBeanProvider(WorkerProperties.class), beans.getBeanProvider(WorkerResourceCommandService.class));
    }
    static WorkerPropertyProjection projection(String group, String event,
            BiFunction<Map<String, Object>, List<Long>, Map<String, Object>> compute) {
        return new WorkerPropertyProjection(group, event, "worker.assigned", compute);
    }
    static WorkerObservation observation(String worker, long time) {
        return new WorkerObservation("g", List.of(worker), time, "event", "worker.assigned");
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
