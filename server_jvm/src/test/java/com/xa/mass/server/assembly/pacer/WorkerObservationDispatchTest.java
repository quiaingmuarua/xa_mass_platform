package com.xa.mass.server.assembly.pacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime.WorkerObservation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Timeout(20)
class WorkerObservationDispatchTest {
    static final EventKey EVENT = new EventKey("event", "worker.assigned");
    static final EventKey OTHER = new EventKey("other", "worker.assigned");
    static final EventKey GATE = new EventKey("gate", "worker.assigned");
    @TempDir Path temporary;

    @Test void oneNoticeIsEnqueuedOnceAndDeliveredToBothHandlersFromAnImmutableRouteSnapshot() throws Exception {
        var done = new CountDownLatch(2);
        var seen = new ConcurrentLinkedQueue<List<WorkerObservation>>();
        FunctionHandler first = batch -> { seen.add(batch); done.countDown(); };
        FunctionHandler second = batch -> { seen.add(batch); done.countDown(); };
        var handlers = new ArrayList<>(List.of(first, second));
        var routes = new LinkedHashMap<EventKey, List<FunctionHandler>>(); routes.put(EVENT, handlers);
        var groups = new LinkedHashMap<String, Map<EventKey, List<FunctionHandler>>>(); groups.put("g", routes);
        var consumer = consumer(groups);
        handlers.clear(); routes.clear(); groups.clear();
        var notice = notice("g", "event", 10);
        try (var recording = new Recording()) {
            recording.enable("xa.mass.WorkerObservation"); recording.start();
            consumer.start();
            try { consumer.accept(notice); await(done); }
            finally { consumer.stop(); }
            recording.stop(); var path = temporary.resolve("fanout.jfr"); recording.dump(path);
            var events = RecordingFile.readAllEvents(path).stream()
                    .filter(event -> event.getEventType().getName().equals("xa.mass.WorkerObservation")).toList();
            assertThat(events).isNotEmpty();
            var summary = events.getLast();
            assertThat(summary.getLong("enqueuedBatches")).isEqualTo(1);
            assertThat(summary.getLong("droppedBatches")).isZero();
            assertThat(summary.getLong("processingFailures")).isZero();
        }
        assertThat(seen).hasSize(2);
        for (var batch : seen) {
            assertThat(batch).containsExactly(notice);
            assertThat(batch.getFirst()).isSameAs(notice);
            assertThatThrownBy(() -> batch.add(notice)).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test void sharedHandlersReceiveOrderedSubsequencesOnceAcrossGroupsAndEvents() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var done = new CountDownLatch(2);
        var order = new ConcurrentLinkedQueue<String>();
        var aBatches = new ConcurrentLinkedQueue<List<WorkerObservation>>();
        var bBatches = new ConcurrentLinkedQueue<List<WorkerObservation>>();
        FunctionHandler a = batch -> { order.add("a"); aBatches.add(batch); done.countDown(); };
        FunctionHandler b = batch -> { order.add("b"); bBatches.add(batch); done.countDown(); };
        var routes = new LinkedHashMap<EventKey, List<FunctionHandler>>();
        routes.put(EVENT, List.of(a, b)); routes.put(OTHER, List.of(b)); routes.put(GATE, List.of(gate(entered, release)));
        var consumer = consumer(Map.of("g", routes, "other-group", Map.of(EVENT, List.of(a))));
        var b20 = notice("g", "other", 20); var a10 = notice("g", "event", 10);
        var a30 = notice("other-group", "event", 30);
        consumer.start();
        try {
            consumer.accept(notice("g", "gate", 0)); await(entered);
            consumer.accept(b20); consumer.accept(a10); consumer.accept(a30); consumer.accept(a10);
            release.countDown(); await(done);
        } finally { release.countDown(); consumer.stop(); }
        assertThat(order).containsExactly("b", "a");
        assertThat(bBatches).containsExactly(List.of(b20, a10, a10));
        assertThat(aBatches).containsExactly(List.of(a10, a30, a10));
    }

    @Test void anOrdinaryHandlerFailureDoesNotPreventTheNextHandlerAndIsNotReplayed() throws Exception {
        var failures = new AtomicLong(); var done = new CountDownLatch(1);
        var order = new ConcurrentLinkedQueue<String>();
        FunctionHandler failing = batch -> { order.add("failed"); throw new IllegalStateException("failure"); };
        FunctionHandler succeeding = batch -> { order.add("succeeded"); done.countDown(); };
        var consumer = new WorkerObservationConsumer(Map.of("g", Map.of(EVENT, List.of(failing, succeeding))),
                new AtomicBoolean(), failures);
        consumer.start();
        try { consumer.accept(notice("g", "event", 1)); await(done); }
        finally { consumer.stop(); }
        assertThat(order).containsExactly("failed", "succeeded");
        assertThat(failures.get()).isEqualTo(1);
    }

    @Test void handlerIdentityRatherThanEqualityControlsRegistrationAndGrouping() throws Exception {
        var done = new CountDownLatch(2); var received = new ConcurrentLinkedQueue<FunctionHandler>();
        class EqualHandler implements FunctionHandler {
            @Override public void handle(List<WorkerObservation> batch) { received.add(this); done.countDown(); }
            @Override public boolean equals(Object other) { return other instanceof EqualHandler; }
            @Override public int hashCode() { return 1; }
        }
        var a = new EqualHandler(); var b = new EqualHandler();
        assertThatThrownBy(() -> consumer(Map.of("g", Map.of(EVENT, List.of(a, a)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicate handler selection");
        var consumer = consumer(Map.of("g", Map.of(EVENT, List.of(a, b))));
        consumer.start();
        try { consumer.accept(notice("g", "event", 1)); await(done); }
        finally { consumer.stop(); }
        var actual = List.copyOf(received);
        assertThat(actual).hasSize(2);
        assertThat(actual.get(0)).isSameAs(a); assertThat(actual.get(1)).isSameAs(b);
    }

    @Test void aDrainRetainsItsBudgetEvenWhenOneHandlerMatchesAllNotices() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        int count = WorkerObservationConsumer.PROCESS_BATCHES + 1;
        var done = new CountDownLatch(count); var batches = new ConcurrentLinkedQueue<List<WorkerObservation>>();
        FunctionHandler handler = batch -> { batches.add(batch); batch.forEach(ignored -> done.countDown()); };
        var consumer = consumer(Map.of("g", Map.of(GATE, List.of(gate(entered, release)), EVENT, List.of(handler))));
        consumer.start();
        try {
            consumer.accept(notice("g", "gate", 0)); await(entered);
            for (int i = 0; i < count; i++) consumer.accept(notice("g", "event", i));
            release.countDown(); await(done);
        } finally { release.countDown(); consumer.stop(); }
        assertThat(batches.stream().map(List::size)).containsExactly(WorkerObservationConsumer.PROCESS_BATCHES, 1);
        assertThat(batches.stream().flatMap(List::stream).map(WorkerObservation::observedAtMillis))
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, count).boxed().toList());
    }

    @Test void closeDuringAHandlerDoesNotInvokeFollowingHandlers() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var following = new AtomicLong();
        FunctionHandler blocking = batch -> { entered.countDown(); awaitIgnoringInterrupt(release, interrupted); };
        var consumer = consumer(Map.of("g", Map.of(EVENT, List.of(blocking, batch -> following.incrementAndGet()))));
        consumer.start();
        try {
            consumer.accept(notice("g", "event", 1)); await(entered);
            var closing = CompletableFuture.runAsync(consumer::stop);
            await(interrupted); assertThat(closing).isNotDone();
            release.countDown(); closing.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); consumer.stop(); }
        assertThat(following.get()).isZero();
    }

    @Test void aStuckHandlerReportsTheBoundedCloseFailureAndCannotBeRestartedUntilItStops() throws Exception {
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        FunctionHandler blocking = batch -> {
            entered.countDown(); awaitIgnoringInterrupt(release, interrupted); finished.countDown();
        };
        var consumer = consumer(Map.of("g", Map.of(EVENT, List.of(blocking))));
        consumer.start();
        try {
            consumer.accept(notice("g", "event", 1)); await(entered);
            assertThatThrownBy(consumer::stop).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exceeded shutdown budget");
            assertThat(consumer.isRunning()).isFalse();
            assertThatThrownBy(consumer::start).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("previous consumer has not stopped");
            assertThatThrownBy(consumer::stop).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("exceeded shutdown budget");
        } finally {
            release.countDown(); await(finished);
            // The prior deadline has expired; wait for the actual consumer thread to terminate.
            org.awaitility.Awaitility.await().ignoreException(IllegalStateException.class)
                    .atMost(java.time.Duration.ofSeconds(5)).untilAsserted(consumer::stop);
        }
        consumer.start(); consumer.stop();
    }

    static WorkerObservationConsumer consumer(Map<String, Map<EventKey, List<FunctionHandler>>> routes) {
        return new WorkerObservationConsumer(routes, new AtomicBoolean(), new AtomicLong());
    }

    static WorkerObservation notice(String group, String event, long time) {
        return new WorkerObservation(group, List.of("w"), time, event, "worker.assigned");
    }

    static FunctionHandler gate(CountDownLatch entered, CountDownLatch release) {
        return batch -> {
            entered.countDown();
            try { await(release); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        };
    }

    static void awaitIgnoringInterrupt(CountDownLatch release, CountDownLatch interrupted) {
        boolean restore = false;
        while (true) {
            try { await(release); break; }
            catch (InterruptedException ignored) { restore = true; interrupted.countDown(); }
        }
        if (restore) Thread.currentThread().interrupt();
    }

    static void await(CountDownLatch latch) throws InterruptedException {
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    }
}
