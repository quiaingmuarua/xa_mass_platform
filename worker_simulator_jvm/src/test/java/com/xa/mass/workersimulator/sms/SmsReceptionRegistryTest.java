package com.xa.mass.workersimulator.sms;

import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SmsReceptionRegistryTest {
    static class Time extends Clock {
        volatile long now = 1000;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
        @Override public long millis() { return now; }
    }
    final Time time = new Time();
    final SmsReceptionRegistry registry = new SmsReceptionRegistry(time, 50_000, 100_000);
    final SmsReceptionRegistry.Sim sim = registry.addSim("g", "one", () -> Map.of("phone", "123", "country", "CN"), () -> "w", () -> "RUNNING", () -> true);
    final List<Map<String, Object>> reports = new CopyOnWriteArrayList<>();
    final List<Long> reportTimes = new CopyOnWriteArrayList<>();
    final WorkerOutcomeReporter reporter = (tag, at, payload) -> {
        assertThat(tag).isEqualTo(9); reports.add(Jsons.parseObject(payload)); reportTimes.add(at); return true;
    };
    Map<String, Object> input(String id, String app) {
        boolean any = app.equals("C");
        return Map.of("messageId", id, "applicationId", app, "country", "CN", "leaseSeconds", 60,
                "setupDeadline", 31_000, "templates", List.of(any ? Map.of("id", "any", "kind", "ANY", "priority", 0)
                        : Map.of("id", "code", "kind", "CODE", "prefix", "[" + app + "] ", "priority", 100)));
    }
    @Test void initialResultHasOriginalIdentityNumberAndAbsoluteDeadline() {
        assertThat(registry.lease(sim, input("m", "A"), reporter)).containsEntry("messageId", "m")
                .containsEntry("phoneNumber", "123").containsEntry("leaseUntil", 61_000L).containsEntry("status", "WAITING");
        assertThat(reports).isEmpty();
    }
    @Test void continuousSameMillisecondMessagesKeepReporterAndAdvanceOrderingTime() {
        registry.lease(sim, input("m", "A"), reporter);
        registry.receive(sim, "s1", "[A] 111111"); registry.receive(sim, "s2", "[A] 222222");
        assertThat(reports).hasSize(2);
        assertThat(reportTimes).containsExactly(1000L, 1001L);
        assertThat(((Map<?, ?>) reports.getLast().get("sms")).get("code")).isEqualTo("222222");
        assertThat(((Number) ((Map<?, ?>) reports.getLast().get("sms")).get("receivedAt")).longValue()).isEqualTo(1000L);
        assertThat(registry.metrics()).containsEntry("activeAssociations", 1);
    }
    @Test void differentAppsShareANumberAndTemplateWinnerKeepsReceiving() {
        registry.lease(sim, input("a", "A"), reporter); registry.lease(sim, input("b", "B"), reporter);
        registry.lease(sim, input("c", "C"), reporter);
        registry.receive(sim, "a1", "[A] 111111"); registry.receive(sim, "b1", "[B] 222222");
        registry.receive(sim, "a2", "[A] 333333"); registry.receive(sim, "c1", "announcement");
        assertThat(reports.stream().map(r -> r.get("messageId"))).containsExactly("a", "b", "a", "c");
        assertThat(registry.metrics()).containsEntry("activeAssociations", 3);
    }
    @Test void expiryClearsAssociationWithoutPublishingOverLatestSms() {
        registry.lease(sim, input("m", "A"), reporter); registry.receive(sim, "s", "[A] 111111");
        time.now = 61_000; registry.expire();
        assertThat(registry.metrics()).containsEntry("activeAssociations", 0);
        assertThat(reports).hasSize(1);
        assertThat(registry.receive(sim, "late", "[A] 222222")).containsEntry("status", "IGNORED");
        assertThat(registry.lease(sim, input("m", "A"), reporter)).containsEntry("trackingStatus", "EXPIRED").containsKey("sms");
    }
    @Test void stopDoesNotRebindOriginalReporterWhenRunRestarts() {
        registry.lease(sim, input("old", "A"), reporter); registry.stop(sim);
        assertThat(registry.lease(sim, input("stopped", "A"), reporter)).containsEntry("status", "REJECTED");
        registry.beginStart(sim); registry.endStart(sim);
        registry.lease(sim, input("old", "A"), (tag, at, body) -> { throw new AssertionError("old association rebound"); });
        registry.lease(sim, input("new", "A"), reporter); registry.receive(sim, "s", "[A] 111111");
        assertThat(reports).singleElement().satisfies(r -> assertThat(r).containsEntry("messageId", "new"));
    }
    @Test void boundedHistoryAndDedupDoNotExhaustActiveCapacityAcrossWindows() {
        var bounded = new SmsReceptionRegistry(time, 2, 2);
        var number = bounded.addSim("g", "n", () -> Map.of("phone", "123", "country", "CN"), () -> "w", () -> "RUNNING", () -> true);
        for (int i = 0; i < 6; i++) {
            var request = new HashMap<>(input("m" + i, "A")); request.put("setupDeadline", time.now + 30_000);
            assertThat(bounded.lease(number, request, reporter)).containsEntry("status", "WAITING");
            bounded.receive(number, "s" + i, "[A] 111111"); time.now += 60_000; bounded.expire();
        }
        assertThat(bounded.metrics()).containsEntry("retainedRecords", 2).containsEntry("smsEvents", 2).containsEntry("activeAssociations", 0);
    }
    @Test void duplicateSmsIsNotForwardedToAnotherAssociation() {
        registry.lease(sim, input("a", "A"), reporter); registry.lease(sim, input("c", "C"), reporter);
        registry.receive(sim, "s", "[A] 111111");
        assertThat(registry.receive(sim, "s", "[A] 111111")).containsEntry("status", "DUPLICATE");
        assertThatThrownBy(() -> registry.receive(sim, "s", "different")).isInstanceOf(IllegalArgumentException.class);
        assertThat(reports).hasSize(1);
    }
    @Test void failedPublicationKeepsAssociationButNeverFallsThrough() {
        registry.lease(sim, input("a", "A"), (tag, at, body) -> false);
        registry.lease(sim, input("c", "C"), reporter);
        registry.receive(sim, "s", "[A] 111111"); registry.receive(sim, "s2", "[A] 222222");
        assertThat(reports).isEmpty();
        assertThat(registry.metrics()).containsEntry("reportFailed", 2L).containsEntry("activeAssociations", 2);
    }
    @Test void reportPublicationDoesNotHoldNumberGateOrDelayStop() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        registry.lease(sim, input("a", "A"), (tag, at, body) -> {
            entered.countDown(); try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } return true;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var sending = executor.submit(() -> registry.receive(sim, "s", "[A] 111111"));
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.submit(() -> registry.stop(sim)).get(1, TimeUnit.SECONDS);
            assertThat(registry.metrics()).containsEntry("activeAssociations", 0);
            release.countDown(); sending.get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }
    @Test void propertyReplacementRevokesOldAddressAndAssociations() {
        var properties = new AtomicReference<>(Map.of("phone", "old", "country", "CN"));
        var number = registry.addSim("g", "dynamic", properties::get, () -> "d", () -> "RUNNING", () -> true);
        registry.lease(number, input("old", "A"), reporter);
        var replacement = Map.of("phone", "new", "country", "CN");
        registry.updateProperties(number, replacement, () -> properties.set(replacement));
        assertThatThrownBy(() -> registry.receive(number, "old", "s", "[A] 111111")).isInstanceOf(IllegalArgumentException.class);
        assertThat(registry.metrics()).containsEntry("activeAssociations", 0);
        assertThat(reports).isEmpty();
    }
}
