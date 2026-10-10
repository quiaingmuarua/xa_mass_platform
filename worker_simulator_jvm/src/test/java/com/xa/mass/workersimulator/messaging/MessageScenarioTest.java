package com.xa.mass.workersimulator.messaging;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.assertj.core.api.Assertions.*;

@Timeout(15)
class MessageScenarioTest {
    static Map<String, Object> send(String id) { return send(id, "{}"); }
    static Map<String, Object> send(String id, String body) {
        return Map.of("campaignId", "campaign", "messageId", id, "country", "CN", "recipientId", "recipient", "body", body);
    }
    static MessageWorkerEndpoint.Sender sender(MessageScenario host, String id) {
        return host.worker().addSender("group", id, () -> Map.of("phone", "+86123", "country", "CN"), () -> id, () -> "RUNNING");
    }
    @Test void realHttpSendReturnsStableSentAndLabAutomaticallyDelivers() throws Exception {
        try (var f = new Network()) {
            var one = sender(f.host, "one"); var two = sender(f.host, "two");
            var reports = new CopyOnWriteArrayList<Map<String, Object>>();
            WorkerOutcomeReporter reporter = (tag, time, payload) -> { reports.add(Jsons.parseObject(payload)); return true; };
            var first = f.host.worker().send(one, send("m"), reporter);
            assertThat(first).containsEntry("status", "SENT").containsEntry("workerId", "one");
            await(() -> reports.size() == 1);
            assertThat(reports.getFirst()).containsEntry("status", "DELIVERED");
            assertThat(f.host.worker().send(two, send("m"), (a, b, c) -> { throw new AssertionError("Wrong Reporter"); })).isEqualTo(first);
            assertThat(f.sends.get()).isEqualTo(2); assertThat(f.callbacks.get()).isEqualTo(1);
            assertThat(f.host.metrics()).containsEntry("reporters", 1).containsEntry("duplicateSends", 1L);
            assertThatThrownBy(() -> f.host.worker().send(one, send("m", "{\"delayMs\":0}"), reporter)).hasMessageContaining("conflict");
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void crossCountrySendAndReceiptKeepRecipientCountryAndActualSender() throws Exception {
        try (var f = new Network()) {
            var worker = f.host.worker().addSender("group", "us", () -> Map.of("phone", "+12025550123", "country", "US"), () -> "us-worker", () -> "RUNNING");
            var reports = new CopyOnWriteArrayList<Map<String, Object>>();
            var request = new LinkedHashMap<>(send("cross-country")); request.put("recipientId", "+8613800000001");
            var sent = f.host.worker().send(worker, request, (tag, time, payload) -> { reports.add(Jsons.parseObject(payload)); return true; });
            assertThat(sent).containsEntry("country", "CN").containsEntry("phone", "+12025550123").containsEntry("workerId", "us-worker");
            await(() -> reports.size() == 1);
            f.host.lab().act(worker.group(), worker.replica(), "cross-country", "reply", Map.of("requestId", "reply", "text", "cross-country reply"));
            await(() -> reports.size() == 2);
            assertThat(reports).allSatisfy(report -> assertThat(report).containsEntry("country", "CN")
                    .containsEntry("recipientId", "+8613800000001").containsEntry("workerId", "us-worker"));
            assertThat(f.sends).hasValue(1); assertThat(f.callbacks).hasValue(2);
            var conflicting = new LinkedHashMap<>(request); conflicting.put("country", "GB");
            assertThatThrownBy(() -> f.host.worker().send(worker, conflicting, WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("conflict");
            conflicting.put("messageId", "other");
            assertThat(f.host.worker().send(worker, conflicting, WorkerOutcomeReporter.UNAVAILABLE)).containsEntry("country", "GB");
        }
    }

    @Test void blockedNetworkCannotBeReplacedByALocalReporterCall() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); var reports = new AtomicInteger();
            f.rejectSend = true;
            assertThatThrownBy(() -> f.host.worker().send(worker, send("rejected"), (a,b,c) -> true)).hasMessageContaining("rejected");
            assertThat(f.host.lab().page(null, null, 0, 100).get("total")).isEqualTo(0);
            f.rejectSend = false; f.rejectCallback = true;
            f.host.worker().send(worker, send("m"), (a,b,c) -> { reports.incrementAndGet(); return true; });
            await(() -> ((Number) f.host.metrics().get("callbackFailed")).intValue() == 1);
            assertThat(reports.get()).isZero(); assertThat(f.row().get("status")).isEqualTo("DELIVERED");
            assertThat(((Map<?,?>)((List<?>) f.row().get("receipts")).getFirst()).get("reportAccepted")).isNull();
            f.rejectCallback = false;
            assertThat(f.host.lab().act(worker.group(), worker.replica(), "m", "read", Map.of())).containsEntry("callbackQueued", true);
            await(() -> reports.get() == 1);
            assertThat(f.callbacks.get()).isEqualTo(2); // No replay of the failed delivered callback.
        }
        try (var host = new MessageScenario(0, MessageSettings.defaults(), Map.of("group", MessageContentMode.LAB_JSON)); var socket = new java.net.ServerSocket(0)) {
            URI missing = URI.create("http://127.0.0.1:" + socket.getLocalPort()); socket.close(); host.startHttp(missing);
            assertThatThrownBy(() -> host.worker().send(sender(host, "one"), send("m"), WorkerOutcomeReporter.UNAVAILABLE))
                    .hasMessageContaining("acceptance unknown");
            assertThat(host.lab().page(null, null, 0, 1).get("total")).isEqualTo(0);
        }
    }

    @Test void simultaneousWorkersShareTheFirstLabAcceptanceAndOnlyItsReporter() throws Exception {
        try (var f = new Network(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = sender(f.host, "one"); var two = sender(f.host, "two");
            var reports = new CopyOnWriteArrayList<String>();
            var first = executor.submit(() -> f.host.worker().send(one, send("m"), (a,b,c) -> { reports.add("one"); return true; }));
            var second = executor.submit(() -> f.host.worker().send(two, send("m"), (a,b,c) -> { reports.add("two"); return true; }));
            var retained = first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(retained);
            await(() -> reports.size() == 1);
            assertThat(reports).containsExactly((String) retained.get("workerId"));
            assertThat(f.host.metrics()).containsEntry("messages", 1).containsEntry("reporters", 1).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void uncertainAssociationsAndSendsHaveIndependentFiniteAdmissionBeforeHttp() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); f.uncertain = true;
            for (int i = 0; i < 1024; i++) {
                String id = "m" + i;
                assertThatThrownBy(() -> f.host.worker().send(worker, send(id), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("uncertain");
            }
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 1024);
            f.uncertain = false;
            assertThat(f.host.worker().send(worker, send("full"), WorkerOutcomeReporter.UNAVAILABLE)).containsEntry("status", "SENT");
            assertThat(f.lastSend.get().get("callbackId")).isNull();
            assertThat(f.sends).hasValue(1025);
            assertThat(f.host.metrics()).containsEntry("skippedAssociations", 1L);
            f.host.stop(worker); assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0);
        }
        try (var f = new Network(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = sender(f.host, "one"); f.responseGate = new CountDownLatch(1);
            List<Future<?>> calls = new ArrayList<>();
            try {
                for (int i = 0; i < 64; i++) {
                    String id = "m" + i;
                    calls.add(executor.submit(() -> f.host.worker().send(worker, send(id), WorkerOutcomeReporter.UNAVAILABLE)));
                }
                await(() -> f.sends.get() == 64);
                for (int i = 0; i < 256; i++) {
                    String id = "queued" + i;
                    calls.add(executor.submit(() -> f.host.worker().send(worker, send(id), WorkerOutcomeReporter.UNAVAILABLE)));
                }
                await(() -> ((Number) f.host.metrics().get("queuedSends")).intValue() == 256);
                assertThatThrownBy(() -> f.host.worker().send(worker, send("full"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("send capacity");
                assertThat(f.sends).hasValue(64);
            } finally { f.responseGate.countDown(); }
            for (Future<?> call : calls) call.get(5, TimeUnit.SECONDS);
            assertThat(f.sends).hasValue(320);
            assertThat(f.host.metrics()).containsEntry("sendInFlight", 0).containsEntry("queuedSends", 0);
        }
    }

    @Test void duplicateResponseResolvesAnUncertainOriginalWithoutRebinding() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); f.host.lab().hold(true); f.loseResponse = true;
            assertThatThrownBy(() -> f.host.worker().send(worker, send("m"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("unknown");
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 1);
            f.loseResponse = false;
            f.host.worker().send(worker, send("m"), (a,b,c) -> { throw new AssertionError("Reporter replaced"); });
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0).containsEntry("reporters", 1);
        }
    }

    @Test void callbackCanPrecedeSendResponseAndLostResponseDoesNotDropOriginalAssociation() throws Exception {
        try (var f = new Network(); var executor = Executors.newSingleThreadExecutor()) {
            var reports = new AtomicInteger(); var worker = sender(f.host, "one");
            f.responseGate = new CountDownLatch(1);
            var sending = executor.submit(() -> f.host.worker().send(worker, send("m"), (a,b,c) -> { reports.incrementAndGet(); return true; }));
            await(() -> reports.get() == 1);
            assertThat(sending.isDone()).isFalse(); assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0);
            f.loseResponse = true; f.responseGate.countDown();
            assertThatThrownBy(() -> sending.get(6, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            f.responseGate = null; f.loseResponse = false;
            f.host.worker().send(worker, send("m"), (a,b,c) -> { throw new AssertionError("Adopted retry Reporter"); });
            f.host.lab().act(worker.group(), worker.replica(), "m", "read", Map.of());
            await(() -> reports.get() == 2);
            assertThat(f.host.metrics()).containsEntry("reporters", 1).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void receiptsCanBeReleasedReversedAndDuplicatedWithOriginalTimestamps() throws Exception {
        try (var f = new Network()) {
            var reports = new CopyOnWriteArrayList<Map<String, Object>>(); var worker = sender(f.host, "one");
            f.host.lab().hold(true);
            f.host.worker().send(worker, send("m"), (a,b,c) -> { reports.add(Jsons.parseObject(c)); return true; });
            String delivered = (String) ((Map<?, ?>)((List<?>) f.row().get("receipts")).getFirst()).get("receiptId");
            String old = (String) f.host.lab().act(worker.group(), worker.replica(), "m", "reply", Map.of("requestId", "r1", "text", "old")).get("receiptId");
            String latest = (String) f.host.lab().act(worker.group(), worker.replica(), "m", "reply", Map.of("requestId", "r2", "text", "latest")).get("receiptId");
            assertThat(f.host.lab().act(worker.group(), worker.replica(), "m", "reply", Map.of("requestId", "r1", "text", "old"))).containsEntry("unchanged", true);
            assertThat(reports).isEmpty();
            assertThat(f.host.lab().release(List.of(latest, latest, old, delivered))).containsEntry("offered", 4).containsEntry("queued", 4L);
            await(() -> reports.size() == 4);
            var newest = reports.stream().filter(r -> "latest".equals(r.get("reply"))).toList();
            assertThat(newest).hasSize(2); assertThat(newest.getFirst()).isEqualTo(newest.getLast());
            assertThat(reports).anySatisfy(r -> assertThat(r).containsEntry("status", "DELIVERED"));
            assertThatThrownBy(() -> f.host.lab().release(List.of(latest))).hasMessageContaining("existing receipt");
        }
    }

    @Test void stopRevokesOldAssociationsWithoutWaitingForReporterAndNewRunCannotAdoptThem() throws Exception {
        try (var f = new Network()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var worker = sender(f.host, "one");
            f.host.worker().send(worker, send("old"), (a,b,c) -> {
                entered.countDown(); try { return release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            f.host.stop(worker); f.host.worker().start(worker); release.countDown();
            f.host.worker().send(worker, send("old"), (a,b,c) -> { throw new AssertionError("Old Reporter adopted"); });
            assertThat(f.host.metrics()).containsEntry("reporters", 0);
            assertThatThrownBy(() -> f.host.lab().act(worker.group(), worker.replica(), "old", "read", Map.of())).isInstanceOf(MessageLab.MissingMessage.class);
            assertThat(f.host.lab().page(null, null, 0, 100).get("items")).isEqualTo(List.of());
            var fresh = new AtomicInteger(); f.host.worker().send(worker, send("new"), (a,b,c) -> { fresh.incrementAndGet(); return true; });
            await(() -> fresh.get() == 1);
        }
    }

    @Test void automaticStepsUseRealCallbacksAndDoNotSealLaterManualReplies() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); var reports = new CopyOnWriteArrayList<Map<String,Object>>();
            f.host.worker().send(worker, send("m", "{\"receipts_status\":[\"read\",\"replied\",\"replied\"],\"delayMs\":0,\"text\":\"auto\"}"),
                    (a,b,c) -> { reports.add(Jsons.parseObject(c)); return true; });
            await(() -> reports.size() == 4);
            assertThat(reports.stream().filter(r -> "REPLIED".equals(r.get("status"))).map(r -> r.get("replyRequestId")).distinct()).hasSize(2);
            f.host.lab().act(worker.group(), worker.replica(), "m", "reply", Map.of("requestId", "manual", "text", "later"));
            await(() -> reports.size() == 5);
            assertThat(f.row()).containsEntry("reply", "later");
        }
    }

    @Test void callbackQueueIsBoundedAndRejectionKeepsFactsWithoutRetry() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); f.callbackGate = new CountDownLatch(1);
            f.host.worker().send(worker, send("m"), WorkerOutcomeReporter.UNAVAILABLE);
            for (int i = 0; i < 150; i++) f.host.lab().act(worker.group(), worker.replica(), "m", "reply", Map.of("requestId", "r" + i, "text", "reply"));
            assertThat(f.row()).containsEntry("replyRequestId", "r149");
            assertThat(((Number) f.host.metrics().get("callbackQueued")).intValue()).isLessThanOrEqualTo(132);
            assertThat(((Number) f.host.metrics().get("callbackFailed")).intValue()).isGreaterThanOrEqualTo(19);
            f.callbackGate.countDown();
        }
    }

    @Test void receiptMustMatchOriginalCorrelationAndSenderButOlderValidReceiptsRemainAdmissible() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); f.host.lab().hold(true);
            f.host.worker().send(worker, send("m"), WorkerOutcomeReporter.UNAVAILABLE);
            var envelope = f.lastSend.get(); var row = new LinkedHashMap<>(f.row()); row.remove("plan"); row.remove("receipts"); row.remove("trackingAvailable");
            var payload = Map.<String,Object>of("callbackId", envelope.get("callbackId"), "receiptId", "receipt-1", "snapshot", row);
            assertThatThrownBy(() -> f.host.worker().receive("group", "wrong", payload)).isInstanceOf(MessageWorkerEndpoint.MissingCorrelation.class);
            row.put("workerId", "wrong");
            assertThatThrownBy(() -> f.host.worker().receive("group", "one", payload)).hasMessageContaining("conflict");
            row.put("workerId", "one"); assertThat(f.host.worker().receive("group", "one", payload)).containsEntry("reportAccepted", false);
        }
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test void stoppedAndInterruptedWaitersNeverStartBusinessHttp() throws Exception {
        try (var f = new Network(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var active = sender(f.host, "active"); var stopped = sender(f.host, "stopped"); var interrupted = sender(f.host, "interrupted");
            f.responseGate = new CountDownLatch(1);
            var admitted = new ArrayList<Future<?>>();
            Future<?> stoppedCall;
            try {
                for (int i = 0; i < 64; i++) {
                    String id = "active-" + i;
                    admitted.add(executor.submit(() -> f.host.worker().send(active, send(id), WorkerOutcomeReporter.UNAVAILABLE)));
                }
                await(() -> f.sends.get() == 64);
                stoppedCall = executor.submit(() -> f.host.worker().send(stopped, send("stopped"), WorkerOutcomeReporter.UNAVAILABLE));
                var interruptedCall = executor.submit(() -> f.host.worker().send(interrupted, send("interrupted"), WorkerOutcomeReporter.UNAVAILABLE));
                await(() -> ((Number) f.host.metrics().get("queuedSends")).intValue() == 2);
                executor.submit(() -> f.host.stop(stopped)).get(1, TimeUnit.SECONDS);
                assertThat(interruptedCall.cancel(true)).isTrue();
                await(() -> ((Number) f.host.metrics().get("queuedSends")).intValue() == 1);
                assertThat(f.sends).hasValue(64);
            } finally { f.responseGate.countDown(); }
            assertThatThrownBy(() -> stoppedCall.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            for (var call : admitted) call.get(5, TimeUnit.SECONDS);
            assertThat(f.sends).hasValue(64);
            assertThat(f.host.metrics()).containsEntry("sendInFlight", 0).containsEntry("queuedSends", 0);
        }
    }

    @Test void plainTextAndFullOrExpiredReporterWindowsDoNotBlockSendingOrRebindDuplicates() throws Exception {
        var nanos = new AtomicLong();
        var host = new MessageScenario(42, new MessageSettings(10_000, 10, 100, 1, 1), Map.of("group", MessageContentMode.TEXT), () -> 1000L, nanos::get);
        try (var f = new Network(host)) {
            var worker = host.worker().addSender("group", "one", () -> Map.of("phone", "+86123", "country", "CN"),
                    () -> "one", () -> "RUNNING");
            var first = host.worker().send(worker, send("first", "  文本 {{name}}\n{}"), (a,b,c) -> { throw new AssertionError("Unexpected receipt"); });
            assertThat(first).containsEntry("status", "SENT").containsEntry("body", "  文本 {{name}}\n{}");
            String originalCallback = (String) f.lastSend.get().get("callbackId");
            var second = host.worker().send(worker, send("second", "{}"), WorkerOutcomeReporter.UNAVAILABLE);
            assertThat(f.lastSend.get().get("callbackId")).isNull();
            assertThat(host.metrics()).containsEntry("acceptedMessages", 2L).containsEntry("skippedAssociations", 1L);
            assertThat(f.callbacks).hasValue(0);
            nanos.set(100_000_000);
            var snapshot = new LinkedHashMap<>(first); snapshot.put("status", "READ");
            assertThatThrownBy(() -> host.worker().receive("group", "one", Map.of("callbackId", originalCallback,
                    "receiptId", "old", "snapshot", snapshot))).isInstanceOf(MessageWorkerEndpoint.MissingCorrelation.class);
            assertThat(host.worker().send(worker, send("second", "{}"), (a,b,c) -> { throw new AssertionError("Rebound untracked send"); })).isEqualTo(second);
            assertThat(host.metrics()).containsEntry("reporters", 0).containsEntry("pendingAssociations", 0);
            host.worker().send(worker, send("fresh", "正文"), WorkerOutcomeReporter.UNAVAILABLE);
            assertThat(host.metrics()).containsEntry("reporters", 1).containsEntry("acceptedMessages", 3L);
        }
    }

    @Test void acceptanceCapacityRejectsBeforeReceivingButExistingIdentityStillConfirms() throws Exception {
        try (var f = new Network(new MessageScenario(42, new MessageSettings(10000, 1, 10000, 1, 1), Map.of("group", MessageContentMode.LAB_JSON)))) {
            var worker = sender(f.host, "one");
            var first = f.host.worker().send(worker, send("first"), WorkerOutcomeReporter.UNAVAILABLE);
            assertThatThrownBy(() -> f.host.worker().send(worker, send("second"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("rejected message acceptance");
            assertThat(f.host.worker().send(worker, send("first"), WorkerOutcomeReporter.UNAVAILABLE)).isEqualTo(first);
            assertThat(f.host.metrics()).containsEntry("acceptedMessages", 1L).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void receivingAdmissionAndStopShareOneCriticalSection() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var receiveClockReads = new AtomicInteger();
        java.util.function.LongSupplier clock = () -> {
            if (Thread.currentThread().getName().equals("message-receive-race") && receiveClockReads.incrementAndGet() == 2) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Admission barrier expired"); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            }
            return 0L;
        };
        var host = new MessageScenario(0, MessageSettings.defaults(), Map.of("group", MessageContentMode.LAB_JSON), () -> 1000L, clock);
        try (var f = new Network(host); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var sender = sender(host, "one"); host.lab().hold(true); f.uncertain = true;
            assertThatThrownBy(() -> host.worker().send(sender, send("race"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("uncertain");
            var envelope = f.lastSend.get();
            var accepting = executor.submit(() -> { Thread.currentThread().setName("message-receive-race"); return host.accept(envelope); });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                var stopped = executor.submit(() -> host.stop(sender));
                assertThatThrownBy(() -> stopped.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown(); accepting.get(5, TimeUnit.SECONDS); stopped.get(5, TimeUnit.SECONDS);
                assertThat(host.metrics()).containsEntry("reporters", 0).containsEntry("pendingAssociations", 0)
                        .containsEntry("trackedMessages", 0).containsEntry("messages", 0).containsEntry("held", 0);
                // A later repeat retains acceptance but cannot recreate its released observation.
                host.worker().start(sender); host.accept(envelope);
                assertThat(host.metrics()).containsEntry("trackedMessages", 0).containsEntry("messages", 0);
            } finally { release.countDown(); }
        }
    }

    static final class Network implements AutoCloseable {
        final MessageScenario host;
        // The fixture intentionally starts 64 sends together, plus callbacks.
        // Do not turn the OS default accept backlog into a send-capacity oracle.
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 128);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger sends = new AtomicInteger(), callbacks = new AtomicInteger();
        final AtomicReference<Map<String,Object>> lastSend = new AtomicReference<>();
        volatile boolean rejectSend, rejectCallback, loseResponse, uncertain;
        volatile CountDownLatch responseGate, callbackGate;
        Network() throws Exception { this(new MessageScenario(42, MessageSettings.defaults(), Map.of("group", MessageContentMode.LAB_JSON))); }
        Network(MessageScenario host) throws Exception {
            this.host = host;
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    var input = Jsons.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    Map<String,Object> result;
                    if (exchange.getRequestURI().getPath().equals("/lab/v1/messages/send")) {
                        sends.incrementAndGet(); lastSend.set(input);
                        if (rejectSend) { exchange.sendResponseHeaders(409, -1); return; }
                        if (uncertain) { exchange.sendResponseHeaders(503, -1); return; }
                        result = host.accept(input);
                        if (responseGate != null && !responseGate.await(8, TimeUnit.SECONDS)) throw new IllegalStateException();
                        if (loseResponse) return;
                    } else {
                        callbacks.incrementAndGet();
                        if (callbackGate != null) callbackGate.await(8, TimeUnit.SECONDS);
                        if (rejectCallback) { exchange.sendResponseHeaders(503, -1); return; }
                        String[] parts = exchange.getRequestURI().getPath().split("/");
                        result = host.worker().receive(parts[4], parts[5].replace(":inputs", ""), (Map<String,Object>) input.get("payload"));
                    }
                    byte[] bytes = Jsons.toJson(result).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (MessageLab.CapacityExceeded full) { exchange.sendResponseHeaders(429, -1); }
                catch (MessageLab.MissingMessage | MessageWorkerEndpoint.MissingCorrelation missing) { exchange.sendResponseHeaders(404, -1); }
                catch (IllegalArgumentException invalid) { exchange.sendResponseHeaders(400, -1); }
                catch (Exception failure) { exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            server.start(); host.startHttp(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        }
        @SuppressWarnings("unchecked") Map<String,Object> row() { return (Map<String,Object>) ((List<?>)host.lab().page(null, null, 0,1).get("items")).getFirst(); }
        public void close() { if (responseGate != null) responseGate.countDown(); if (callbackGate != null) callbackGate.countDown(); host.close(); server.stop(0); executor.shutdownNow(); }
    }
}
