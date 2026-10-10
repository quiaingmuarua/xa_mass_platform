package com.xa.mass.workersimulator.messaging;

import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** No receiving service, HTTP client or listener is constructed in this proof. */
class MessageWorkerEndpointTest {
    static Map<String, Object> message(String id) {
        return Map.of("campaignId", "task", "messageId", id, "country", "CN", "recipientId", "+86123", "body", "  内容 {{name}}\n{}");
    }
    static MessageWorkerEndpoint.Sender sender(MessageWorkerEndpoint endpoint, String id) {
        return endpoint.addSender("group", id, () -> Map.of("phone", "+86100", "country", "CN"), () -> id, () -> "RUNNING");
    }
    static MessageSendOperation.Acceptance accepted(Map<String, Object> message, Map<String, Object> sender, String callback) {
        var sent = new LinkedHashMap<>(message);
        sent.put("status", "SENT"); sent.put("workerId", sender.get("workerId")); sent.put("phone", sender.get("phone"));
        sent.put("observedAtMillis", 1000L);
        return new MessageSendOperation.Acceptance(sent, callback);
    }
    static Map<String, Object> receipt(MessageSendOperation.Acceptance acceptance) {
        var snapshot = new LinkedHashMap<>(acceptance.snapshot()); snapshot.put("status", "DELIVERED");
        return Map.of("callbackId", acceptance.callbackId(), "receiptId", "receipt", "snapshot", snapshot);
    }
    static MessageWorkerEndpoint endpoint(MessageSendOperation operation, AtomicLong nanos) {
        var result = new MessageWorkerEndpoint(operation, new MessageSettings(1000, 20, 100, 1, 1), nanos::get);
        result.start(); return result;
    }

    @Test void sendingPreservesOpaqueContentAndAnEarlyReceiptUsesTheOriginalReporter() {
        var endpoint = new AtomicReference<MessageWorkerEndpoint>(); var reports = new AtomicInteger();
        MessageSendOperation operation = (message, sender, callback, remaining) -> {
            assertThat(remaining.toNanos()).isPositive().isLessThanOrEqualTo(5_000_000_000L);
            var acceptance = accepted(message, sender, callback);
            assertThat(endpoint.get().receive("group", "one", receipt(acceptance))).containsEntry("reportAccepted", true);
            assertThat(reports).hasValue(1);
            return acceptance;
        };
        try (var owner = endpoint(operation, new AtomicLong())) {
            endpoint.set(owner);
            var sent = owner.send(sender(owner, "one"), message("m"), (tag, time, content) -> {
                assertThat(tag).isEqualTo(7); reports.incrementAndGet(); return true;
            });
            assertThat(sent).containsEntry("body", message("m").get("body")).containsEntry("status", "SENT");
            assertThat(owner.metrics()).containsEntry("reporters", 1).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void rejectionsReleaseReservationsButUnconfirmedAttemptsKeepTheirFixedWindow() {
        var nanos = new AtomicLong(); var original = new AtomicReference<MessageSendOperation.Acceptance>();
        MessageSendOperation operation = (message, sender, callback, remaining) -> {
            return switch ((String) message.get("messageId")) {
                case "invalid" -> throw new MessageSendOperation.Rejected(true, "input rejected");
                case "capacity" -> throw new MessageSendOperation.Rejected(false, "capacity rejected");
                case "unknown" -> { original.set(accepted(message, sender, callback)); throw new IOException("unconfirmed"); }
                default -> accepted(message, sender, callback);
            };
        };
        try (var owner = endpoint(operation, nanos)) {
            var sender = sender(owner, "one");
            assertThatThrownBy(() -> owner.send(sender, message("invalid"), WorkerOutcomeReporter.UNAVAILABLE)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> owner.send(sender, message("capacity"), WorkerOutcomeReporter.UNAVAILABLE)).isInstanceOf(IllegalStateException.class);
            assertThat(owner.metrics()).containsEntry("pendingAssociations", 0);
            assertThatThrownBy(() -> owner.send(sender, message("unknown"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("acceptance unknown");
            assertThat(owner.metrics()).containsEntry("pendingAssociations", 1);
            assertThat(owner.send(sender, message("untracked"), WorkerOutcomeReporter.UNAVAILABLE)).containsEntry("status", "SENT");
            assertThat(owner.metrics()).containsEntry("skippedAssociations", 1L).containsEntry("pendingAssociations", 1);
            nanos.set(100_000_000L);
            assertThatThrownBy(() -> owner.receive("group", "one", receipt(original.get())))
                    .isInstanceOf(MessageWorkerEndpoint.MissingCorrelation.class);
            owner.send(sender, message("fresh"), WorkerOutcomeReporter.UNAVAILABLE);
            assertThat(owner.metrics()).containsEntry("reporters", 1).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void uncertainFirstAcceptanceIsReconciledAcrossWorkersWithoutRebindingAfterStop() {
        var original = new AtomicReference<MessageSendOperation.Acceptance>();
        var attempts = new AtomicInteger(); var firstReports = new AtomicInteger();
        MessageSendOperation operation = (message, sender, callback, remaining) -> {
            if (attempts.getAndIncrement() == 0) {
                original.set(accepted(message, sender, callback)); throw new IOException("response lost");
            }
            return original.get();
        };
        try (var owner = endpoint(operation, new AtomicLong())) {
            var first = sender(owner, "first"); var retry = sender(owner, "retry");
            assertThatThrownBy(() -> owner.send(first, message("m"), (a,b,c) -> { firstReports.incrementAndGet(); return true; }))
                    .hasMessageContaining("acceptance unknown");
            assertThat(owner.send(retry, message("m"), (a,b,c) -> { throw new AssertionError("Reporter rebound"); }))
                    .containsEntry("workerId", "first");
            owner.receive("group", "first", receipt(original.get()));
            assertThat(firstReports).hasValue(1);
            owner.stopWithCleanup(first, () -> {});
            owner.send(retry, message("m"), (a,b,c) -> { throw new AssertionError("Stopped Reporter rebound"); });
            assertThat(owner.metrics()).containsEntry("reporters", 0).containsEntry("pendingAssociations", 0);
            assertThatThrownBy(() -> owner.receive("group", "first", receipt(original.get())))
                    .isInstanceOf(MessageWorkerEndpoint.MissingCorrelation.class);
        }
    }
}
