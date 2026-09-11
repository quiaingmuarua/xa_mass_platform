package com.xa.mass.workersimulator.messaging;

import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MessageScenarioTest {
    Map<String, Object> send(String id) {
        return Map.of("campaignId", "campaign", "messageId", id, "country", "CN", "recipientId", "recipient", "body", "body");
    }
    MessageScenario.Sender sender(MessageScenario host, String id) {
        return host.addSender("demo-sim", id, () -> Map.of("country", "CN", "phone", "phone-" + id), () -> id, () -> "RUNNING");
    }
    @Test void handlerDeduplicatesAcrossWorkersAndRetainsFirstReporter() {
        try (var host = new MessageScenario()) {
            var reports = new ArrayList<String>();
            var one = sender(host, "one"); var two = sender(host, "two");
            assertThat(host.send(one, send("m"), (tag, time, payload) -> { reports.add(payload); return true; })).containsEntry("status", "SENT");
            assertThat(host.send(two, send("m"), (tag, time, payload) -> { throw new AssertionError("Reporter replaced"); })).containsEntry("workerId", "one");
            assertThat(host.act(one, "m", "deliver", Map.of())).containsEntry("sendAccepted", true);
            assertThat(reports).hasSize(1);
            var changed = new HashMap<>(send("m")); changed.put("body", "different");
            assertThatThrownBy(() -> host.send(one, changed, WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("conflict");
            assertThat(host.page(0, 100).get("total")).isEqualTo(1);
        }
    }
    @Test void receiptReleaseCanReorderAndDuplicateOnlyCommittedFacts() {
        try (var host = new MessageScenario()) {
            var reports = new ArrayList<Map<String, Object>>();
            var sender = sender(host, "one");
            host.send(sender, send("m"), (tag, time, payload) -> { reports.add(Jsons.parseObject(payload)); return true; });
            host.hold(true);
            String delivered = (String) host.act(sender, "m", "deliver", Map.of()).get("receiptId");
            String old = (String) host.act(sender, "m", "reply", Map.of("requestId", "r1", "text", "old")).get("receiptId");
            String latest = (String) host.act(sender, "m", "reply", Map.of("requestId", "r2", "text", "latest")).get("receiptId");
            assertThat(host.act(sender, "m", "reply", Map.of("requestId", "r1", "text", "old"))).containsEntry("unchanged", true);
            assertThat(reports).isEmpty();
            assertThatThrownBy(() -> host.release(List.of("unknown"))).hasMessageContaining("existing receipt");
            host.release(List.of(latest, latest, old, delivered));
            assertThat(reports).hasSize(4); assertThat(reports.get(0)).isEqualTo(reports.get(1));
            assertThat(reports.get(0)).containsEntry("reply", "latest");
            assertThat(((Number) reports.get(0).get("observedAtMillis")).longValue())
                    .isGreaterThan(((Number) reports.get(2).get("observedAtMillis")).longValue());
            assertThatThrownBy(() -> host.release(List.of(latest))).hasMessageContaining("existing receipt");
        }
    }
    @Test void publishRunsOutsideGateAndStopDoesNotTransferOldReporter() throws Exception {
        try (var host = new MessageScenario(); var executor = Executors.newSingleThreadExecutor()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var sender = sender(host, "one");
            host.send(sender, send("old"), (tag, time, payload) -> {
                entered.countDown(); try { return release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            });
            var action = executor.submit(() -> host.act(sender, "old", "deliver", Map.of()));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            host.stop(sender); host.start(sender);
            assertThat(host.act(sender, "old", "read", Map.of())).containsEntry("persisted", true).containsEntry("sendAccepted", false);
            release.countDown(); action.get(2, TimeUnit.SECONDS);
            host.send(sender, send("old"), (tag, time, payload) -> { throw new AssertionError("Old reporter rebound"); });
            assertThat(host.act(sender, "old", "reply", Map.of("requestId", "r", "text", "reply"))).containsEntry("sendAccepted", false);
            host.send(sender, send("new"), (tag, time, payload) -> true);
            assertThat(host.act(sender, "new", "deliver", Map.of())).containsEntry("sendAccepted", true);
        }
    }
    @Test void failedSendRetainsLocalFactWithoutRetry() {
        try (var host = new MessageScenario()) {
            var sender = sender(host, "one");
            host.send(sender, send("m"), (tag, time, payload) -> { throw new IllegalStateException("offline"); });
            assertThat(host.act(sender, "m", "deliver", Map.of())).containsEntry("persisted", true).containsEntry("sendAccepted", false);
            assertThat(host.act(sender, "m", "deliver", Map.of())).containsEntry("unchanged", true);
            assertThat(host.metrics()).containsEntry("published", 1L).containsEntry("sendFailed", 1L);
        }
    }
    @Test void capacityRejectionCannotPartiallyCommitARecipientAction() {
        try (var host = new MessageScenario()) {
            var sender = sender(host, "one");
            host.send(sender, send("m"), WorkerOutcomeReporter.UNAVAILABLE);
            host.hold(true); host.act(sender, "m", "deliver", Map.of());
            for (int i = 1; i < MessageScenario.MAX_HELD; i++)
                host.act(sender, "m", "reply", Map.of("requestId", "r" + i, "text", "reply" + i));
            var previous = host.page(0, 1);
            assertThatThrownBy(() -> host.act(sender, "m", "reply", Map.of("requestId", "overflow", "text", "not committed")))
                    .hasMessageContaining("capacity exhausted");
            assertThat(host.page(0, 1)).isEqualTo(previous);
            assertThat(host.metrics()).containsEntry("held", MessageScenario.MAX_HELD).containsEntry("published", 0L);
        }
    }
}
