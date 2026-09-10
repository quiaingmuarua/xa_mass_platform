package com.xa.mass.sms.simulator;

import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ListeningRegistryTest {
    static final class Time extends Clock {
        volatile long now = 1_000_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
        public long millis() { return now; }
    }
    final Time time = new Time();
    final ListeningRegistry registry = new ListeningRegistry(time, 50_000, 100_000);
    final ListeningRegistry.Sim first = registry.addSim("100", "CN", () -> "worker-1", () -> "RUNNING");
    final ListeningRegistry.Sim second = registry.addSim("101", "CN", () -> "worker-2", () -> "RUNNING");
    final List<Map<String, Object>> reports = new CopyOnWriteArrayList<>();
    final WorkerOutcomeReporter reporter = (tag, at, payload) -> {
        assertThat(tag).isEqualTo(9); reports.add(Jsons.parseObject(payload)); return true;
    };
    Map<String, Object> input(String id, int priority, String kind) {
        return Map.of("listenerId", id, "applicationId", "A", "country", "CN", "listenSeconds", 60,
                "setupDeadline", 1_030_000L, "templates", List.of(Map.of("id", "test", "priority", priority,
                        "kind", kind, "prefix", "[A] ")));
    }
    @Test void oneSmsChoosesHighestPriorityThenOldestAndDuplicateCannotReachAnotherOrder() {
        registry.listen(first, input("wildcard", 0, "ANY"), reporter);
        registry.listen(first, input("older", 200, "CODE"), reporter);
        registry.listen(first, input("younger", 200, "CODE"), reporter);
        assertThat(registry.receive("100", "sms1", "[A] 123456")).containsEntry("listenerId", "older");
        assertThat(registry.receive("100", "sms1", "[A] 123456")).containsEntry("status", "DUPLICATE");
        assertThat(registry.receive("100", "sms2", "[A] 123456")).containsEntry("listenerId", "younger");
        assertThat(registry.receive("100", "sms3", "announcement")).containsEntry("listenerId", "wildcard");
        assertThat(reports).hasSize(3);
        assertThat(registry.metrics()).containsEntry("activeListeners", 0);
        assertThat(reports.getFirst()).containsEntry("phone", "100").containsEntry("workerId", "worker-1");
    }
    @Test void globalDuplicateOnAnotherReplicaRetainsOriginalReporterAndFullFinalSnapshot() {
        registry.listen(first, input("one", 100, "CODE"), reporter);
        AtomicInteger otherReports = new AtomicInteger();
        assertThat(registry.listen(second, input("one", 100, "CODE"), (tag, at, body) -> {
            otherReports.incrementAndGet(); return true;
        })).containsEntry("phone", "100");
        registry.receive("100", "s1", "[A] 111111");
        assertThat(registry.listen(second, input("one", 100, "CODE"), WorkerOutcomeReporter.UNAVAILABLE))
                .containsEntry("status", "RECEIVED").containsKey("sms").containsEntry("phone", "100");
        assertThat(otherReports).hasValue(0);
        assertThat(registry.metrics()).containsEntry("listeners", 1);
        assertThatThrownBy(() -> registry.listen(second, input("one", 0, "ANY"), reporter))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void noListenerNoMatchAndWindowBoundaryNeverReportSms() {
        registry.receive("100", "none", "[A] 123456");
        registry.listen(first, input("one", 100, "CODE"), reporter);
        registry.receive("100", "noise", "unmatched");
        assertThat(reports).isEmpty();
        time.now += 60_000;
        registry.receive("100", "late", "[A] 123456");
        assertThat(reports).singleElement().satisfies(r -> assertThat(r).containsEntry("status", "EXPIRED").doesNotContainKey("sms"));
        assertThat(registry.metrics()).containsEntry("matched", 0L);
    }
    @Test void cancelAndSmsRacesHaveExactlyOneWinnerAndPublishOutsideNumberLock() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            for (int iteration = 0; iteration < 100; iteration++) {
                String id = "race-" + iteration;
                registry.listen(first, input(id, 100, "CODE"), reporter);
                CyclicBarrier start = new CyclicBarrier(2);
                Future<?> cancel = pool.submit(() -> { await(start); registry.cancel(first, Map.of("listenerId", id)); });
                Future<?> sms = pool.submit(() -> { await(start); registry.receive("100", id, "[A] 123456"); });
                cancel.get(2, TimeUnit.SECONDS); sms.get(2, TimeUnit.SECONDS);
            }
        }
        assertThat(reports).hasSize(100);
        assertThat(reports.stream().map(r -> r.get("listenerId")).distinct()).hasSize(100);
    }
    @Test void expiryAndSmsRacesAtBoundaryOnlyExpire() throws Exception {
        registry.listen(first, input("edge", 10, "CODE"), reporter);
        time.now += 60_000;
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> expire = executor.submit(registry::expire);
            Future<?> sms = executor.submit(() -> registry.receive("100", "edge-sms", "[A] 000000"));
            expire.get(2, TimeUnit.SECONDS); sms.get(2, TimeUnit.SECONDS);
        }
        assertThat(reports).singleElement().satisfies(r -> assertThat(r).containsEntry("status", "EXPIRED"));
    }
    @Test void failedPublicationDoesNotFallThroughAndClosedRunCannotKeepReporter() {
        registry.listen(first, input("first", 100, "CODE"), WorkerOutcomeReporter.UNAVAILABLE);
        registry.listen(first, input("fallback", 0, "ANY"), reporter);
        registry.receive("100", "sms", "[A] 123456");
        assertThat(reports).isEmpty();
        assertThat(registry.metrics()).containsEntry("activeListeners", 1).containsEntry("reportFailed", 1L);
        registry.close();
        assertThat(registry.metrics()).containsEntry("activeListeners", 0);
        registry.expire();
        assertThat(reports).isEmpty();
        assertThat(registry.listen(first, input("new", 1, "ANY"), reporter)).containsEntry("status", "REJECTED");
    }
    @Test void boundedRecordsNeverEvictDedupAndExistingListenersContinue() {
        var bounded = new ListeningRegistry(time, 2, 2);
        var sim = bounded.addSim("100", "CN", () -> "w", () -> "RUNNING");
        bounded.listen(sim, input("a", 0, "ANY"), reporter);
        bounded.listen(sim, input("b", 0, "ANY"), reporter);
        assertThat(bounded.listen(sim, input("c", 0, "ANY"), reporter)).containsEntry("status", "REJECTED");
        bounded.receive("100", "one", "x"); bounded.receive("100", "two", "x");
        assertThatThrownBy(() -> bounded.receive("100", "three", "x")).isInstanceOf(IllegalStateException.class);
        assertThat(bounded.receive("100", "one", "x")).containsEntry("status", "DUPLICATE");
        assertThatThrownBy(() -> bounded.receive("100", "one", "different")).isInstanceOf(IllegalArgumentException.class);
        assertThat(reports).hasSize(2);
        for (int i = 0; i < 64; i++) registry.listen(first, input("cap-" + i, 0, "ANY"), reporter);
        assertThat(registry.listen(first, input("overflow", 0, "ANY"), reporter)).containsEntry("status", "REJECTED");
        registry.cancel(first, Map.of("listenerId", "cap-0"));
        assertThat(registry.listen(second, input("overflow", 0, "ANY"), reporter)).containsEntry("status", "REJECTED");
    }
    @Test void smsArrivalBeforeEstablishmentIsNotEligibleEvenIfProcessedWithLaterState() {
        registry.listen(first, input("later", 100, "CODE"), reporter);
        time.now -= 1;
        assertThat(registry.receive("100", "earlier", "[A] 123456")).containsEntry("status", "IGNORED");
        assertThat(reports).isEmpty();
    }
    @Test void slowNetworkSendDoesNotHoldNumberStateLock() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        registry.listen(first, input("slow", 100, "CODE"), (tag, at, body) -> {
            entered.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return true;
        });
        registry.listen(first, input("other", 0, "ANY"), reporter);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var sms = executor.submit(() -> registry.receive("100", "slow-sms", "[A] 123456"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var cancel = executor.submit(() -> registry.cancel(first, Map.of("listenerId", "other")));
            assertThat(cancel.get(1, TimeUnit.SECONDS)).containsEntry("status", "CANCELLED");
            release.countDown(); sms.get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
    private static void await(CyclicBarrier barrier) {
        try { barrier.await(2, TimeUnit.SECONDS); } catch (Exception error) { throw new AssertionError(error); }
    }
    @Test void inventoryIsBoundedAndReportsLocalStateAndActiveCount() {
        registry.listen(first, input("active", 100, "CODE"), reporter);
        assertThat(registry.inventory(0, 1)).containsEntry("total", 2).containsEntry("limit", 1);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) registry.inventory(0, 1).get("items");
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("phone", "100").containsEntry("runtimeState", "RUNNING")
                .containsEntry("activeListeners", 1));
        assertThat((List<?>) registry.inventory(2, 1).get("items")).isEmpty();
        assertThatThrownBy(() -> registry.inventory(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry.inventory(0, 1001)).isInstanceOf(IllegalArgumentException.class);
    }

}
