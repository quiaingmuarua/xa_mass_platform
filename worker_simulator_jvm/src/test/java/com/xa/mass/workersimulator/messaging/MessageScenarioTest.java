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
    static MessageScenario.Sender sender(MessageScenario host, String id) {
        return host.addSender("group", id, () -> Map.of("phone", "+86123", "country", "CN"), () -> id, () -> "RUNNING");
    }
    @Test void realHttpSendReturnsStableSentAndLabAutomaticallyDelivers() throws Exception {
        try (var f = new Network()) {
            var one = sender(f.host, "one"); var two = sender(f.host, "two");
            var reports = new CopyOnWriteArrayList<Map<String, Object>>();
            WorkerOutcomeReporter reporter = (tag, time, payload) -> { reports.add(Jsons.parseObject(payload)); return true; };
            var first = f.host.send(one, send("m"), reporter);
            assertThat(first).containsEntry("status", "SENT").containsEntry("workerId", "one");
            await(() -> reports.size() == 1);
            assertThat(reports.getFirst()).containsEntry("status", "DELIVERED");
            assertThat(f.host.send(two, send("m"), (a, b, c) -> { throw new AssertionError("Wrong Reporter"); })).isEqualTo(first);
            assertThat(f.sends.get()).isEqualTo(2); assertThat(f.callbacks.get()).isEqualTo(1);
            assertThat(f.host.metrics()).containsEntry("reporters", 1).containsEntry("duplicateSends", 1L);
            assertThatThrownBy(() -> f.host.send(one, send("m", "{\"delayMs\":0}"), reporter)).hasMessageContaining("conflict");
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void blockedNetworkCannotBeReplacedByALocalReporterCall() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); var reports = new AtomicInteger();
            f.rejectSend = true;
            assertThatThrownBy(() -> f.host.send(worker, send("rejected"), (a,b,c) -> true)).hasMessageContaining("rejected");
            assertThat(f.host.page(0, 100).get("total")).isEqualTo(0);
            f.rejectSend = false; f.rejectCallback = true;
            f.host.send(worker, send("m"), (a,b,c) -> { reports.incrementAndGet(); return true; });
            await(() -> ((Number) f.host.metrics().get("callbackFailed")).intValue() == 1);
            assertThat(reports.get()).isZero(); assertThat(f.row().get("status")).isEqualTo("DELIVERED");
            assertThat(((Map<?,?>)((List<?>) f.row().get("receipts")).getFirst()).get("reportAccepted")).isNull();
            f.rejectCallback = false;
            assertThat(f.host.act(worker, "m", "read", Map.of())).containsEntry("callbackQueued", true);
            await(() -> reports.get() == 1);
            assertThat(f.callbacks.get()).isEqualTo(2); // No replay of the failed delivered callback.
        }
        try (var host = new MessageScenario(); var socket = new java.net.ServerSocket(0)) {
            URI missing = URI.create("http://127.0.0.1:" + socket.getLocalPort()); socket.close(); host.startHttp(missing);
            assertThatThrownBy(() -> host.send(sender(host, "one"), send("m"), WorkerOutcomeReporter.UNAVAILABLE))
                    .hasMessageContaining("acceptance unknown");
            assertThat(host.page(0, 1).get("total")).isEqualTo(0);
        }
    }

    @Test void simultaneousWorkersShareTheFirstLabAcceptanceAndOnlyItsReporter() throws Exception {
        try (var f = new Network(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = sender(f.host, "one"); var two = sender(f.host, "two");
            var reports = new CopyOnWriteArrayList<String>();
            var first = executor.submit(() -> f.host.send(one, send("m"), (a,b,c) -> { reports.add("one"); return true; }));
            var second = executor.submit(() -> f.host.send(two, send("m"), (a,b,c) -> { reports.add("two"); return true; }));
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
                assertThatThrownBy(() -> f.host.send(worker, send(id), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("uncertain");
            }
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 1024);
            assertThatThrownBy(() -> f.host.send(worker, send("full"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("capacity");
            assertThat(f.sends).hasValue(1024);
            f.host.stop(worker); assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0);
        }
        try (var f = new Network(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = sender(f.host, "one"); f.responseGate = new CountDownLatch(1);
            List<Future<?>> calls = new ArrayList<>();
            try {
                for (int i = 0; i < 64; i++) {
                    String id = "m" + i;
                    calls.add(executor.submit(() -> f.host.send(worker, send(id), WorkerOutcomeReporter.UNAVAILABLE)));
                }
                await(() -> f.sends.get() == 64);
                assertThatThrownBy(() -> f.host.send(worker, send("full"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("send capacity");
                assertThat(f.sends).hasValue(64);
            } finally { f.responseGate.countDown(); }
            for (Future<?> call : calls) call.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void duplicateResponseResolvesAnUncertainOriginalWithoutRebinding() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); f.host.hold(true); f.loseResponse = true;
            assertThatThrownBy(() -> f.host.send(worker, send("m"), WorkerOutcomeReporter.UNAVAILABLE)).hasMessageContaining("unknown");
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 1);
            f.loseResponse = false;
            f.host.send(worker, send("m"), (a,b,c) -> { throw new AssertionError("Reporter replaced"); });
            assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0).containsEntry("reporters", 1);
        }
    }

    @Test void callbackCanPrecedeSendResponseAndLostResponseDoesNotDropOriginalAssociation() throws Exception {
        try (var f = new Network(); var executor = Executors.newSingleThreadExecutor()) {
            var reports = new AtomicInteger(); var worker = sender(f.host, "one");
            f.responseGate = new CountDownLatch(1);
            var sending = executor.submit(() -> f.host.send(worker, send("m"), (a,b,c) -> { reports.incrementAndGet(); return true; }));
            await(() -> reports.get() == 1);
            assertThat(sending.isDone()).isFalse(); assertThat(f.host.metrics()).containsEntry("pendingAssociations", 0);
            f.loseResponse = true; f.responseGate.countDown();
            assertThatThrownBy(() -> sending.get(6, TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
            f.responseGate = null; f.loseResponse = false;
            f.host.send(worker, send("m"), (a,b,c) -> { throw new AssertionError("Adopted retry Reporter"); });
            f.host.act(worker, "m", "read", Map.of());
            await(() -> reports.get() == 2);
            assertThat(f.host.metrics()).containsEntry("reporters", 1).containsEntry("pendingAssociations", 0);
        }
    }

    @Test void receiptsCanBeReleasedReversedAndDuplicatedWithOriginalTimestamps() throws Exception {
        try (var f = new Network()) {
            var reports = new CopyOnWriteArrayList<Map<String, Object>>(); var worker = sender(f.host, "one");
            f.host.hold(true);
            f.host.send(worker, send("m"), (a,b,c) -> { reports.add(Jsons.parseObject(c)); return true; });
            String delivered = (String) ((Map<?, ?>)((List<?>) f.row().get("receipts")).getFirst()).get("receiptId");
            String old = (String) f.host.act(worker, "m", "reply", Map.of("requestId", "r1", "text", "old")).get("receiptId");
            String latest = (String) f.host.act(worker, "m", "reply", Map.of("requestId", "r2", "text", "latest")).get("receiptId");
            assertThat(f.host.act(worker, "m", "reply", Map.of("requestId", "r1", "text", "old"))).containsEntry("unchanged", true);
            assertThat(reports).isEmpty();
            assertThat(f.host.release(List.of(latest, latest, old, delivered))).containsEntry("offered", 4).containsEntry("queued", 4L);
            await(() -> reports.size() == 4);
            var newest = reports.stream().filter(r -> "latest".equals(r.get("reply"))).toList();
            assertThat(newest).hasSize(2); assertThat(newest.getFirst()).isEqualTo(newest.getLast());
            assertThat(reports).anySatisfy(r -> assertThat(r).containsEntry("status", "DELIVERED"));
            assertThatThrownBy(() -> f.host.release(List.of(latest))).hasMessageContaining("existing receipt");
        }
    }

    @Test void stopRevokesOldAssociationsWithoutWaitingForReporterAndNewRunCannotAdoptThem() throws Exception {
        try (var f = new Network()) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var worker = sender(f.host, "one");
            f.host.send(worker, send("old"), (a,b,c) -> {
                entered.countDown(); try { return release.await(3, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            });
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            f.host.stop(worker); f.host.start(worker); release.countDown();
            f.host.send(worker, send("old"), (a,b,c) -> { throw new AssertionError("Old Reporter adopted"); });
            assertThat(f.host.metrics()).containsEntry("reporters", 0);
            f.host.act(worker, "old", "read", Map.of());
            await(() -> ((Number) f.host.metrics().get("callbackFailed")).intValue() == 1);
            var fresh = new AtomicInteger(); f.host.send(worker, send("new"), (a,b,c) -> { fresh.incrementAndGet(); return true; });
            await(() -> fresh.get() == 1);
        }
    }

    @Test void automaticStepsUseRealCallbacksAndDoNotSealLaterManualReplies() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); var reports = new CopyOnWriteArrayList<Map<String,Object>>();
            f.host.send(worker, send("m", "{\"receipts_status\":[\"read\",\"replied\",\"replied\"],\"delayMs\":0,\"text\":\"auto\"}"),
                    (a,b,c) -> { reports.add(Jsons.parseObject(c)); return true; });
            await(() -> reports.size() == 4);
            assertThat(reports.stream().filter(r -> "REPLIED".equals(r.get("status"))).map(r -> r.get("replyRequestId")).distinct()).hasSize(2);
            f.host.act(worker, "m", "reply", Map.of("requestId", "manual", "text", "later"));
            await(() -> reports.size() == 5);
            assertThat(f.row()).containsEntry("reply", "later");
        }
    }

    @Test void callbackQueueIsBoundedAndRejectionKeepsFactsWithoutRetry() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); f.callbackGate = new CountDownLatch(1);
            f.host.send(worker, send("m"), WorkerOutcomeReporter.UNAVAILABLE);
            for (int i = 0; i < 150; i++) f.host.act(worker, "m", "reply", Map.of("requestId", "r" + i, "text", "reply"));
            assertThat(f.row()).containsEntry("replyRequestId", "r149");
            assertThat(((Number) f.host.metrics().get("callbackQueued")).intValue()).isLessThanOrEqualTo(132);
            assertThat(((Number) f.host.metrics().get("callbackFailed")).intValue()).isGreaterThanOrEqualTo(19);
            f.callbackGate.countDown();
        }
    }

    @Test void receiptMustMatchOriginalCorrelationAndSenderButOlderValidReceiptsRemainAdmissible() throws Exception {
        try (var f = new Network()) {
            var worker = sender(f.host, "one"); f.host.hold(true);
            f.host.send(worker, send("m"), WorkerOutcomeReporter.UNAVAILABLE);
            var envelope = f.lastSend.get(); var row = new LinkedHashMap<>(f.row()); row.remove("plan"); row.remove("receipts");
            var payload = Map.<String,Object>of("callbackId", envelope.get("callbackId"), "receiptId", "receipt-1", "snapshot", row);
            assertThatThrownBy(() -> f.host.receive("group", "wrong", payload)).isInstanceOf(MessageScenario.MissingMessage.class);
            row.put("workerId", "wrong");
            assertThatThrownBy(() -> f.host.receive("group", "one", payload)).hasMessageContaining("conflict");
            row.put("workerId", "one"); assertThat(f.host.receive("group", "one", payload)).containsEntry("reportAccepted", false);
        }
    }

    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertThat(condition.getAsBoolean()).isTrue();
    }

    static final class Network implements AutoCloseable {
        final MessageScenario host = new MessageScenario(42);
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger sends = new AtomicInteger(), callbacks = new AtomicInteger();
        final AtomicReference<Map<String,Object>> lastSend = new AtomicReference<>();
        volatile boolean rejectSend, rejectCallback, loseResponse, uncertain;
        volatile CountDownLatch responseGate, callbackGate;
        Network() throws Exception {
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
                        result = host.receive(parts[4], parts[5].replace(":inputs", ""), (Map<String,Object>) input.get("payload"));
                    }
                    byte[] bytes = Jsons.toJson(result).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (MessageScenario.MissingMessage missing) { exchange.sendResponseHeaders(404, -1); }
                catch (IllegalArgumentException invalid) { exchange.sendResponseHeaders(400, -1); }
                catch (Exception failure) { exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            });
            server.start(); host.startHttp(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        }
        @SuppressWarnings("unchecked") Map<String,Object> row() { return (Map<String,Object>) ((List<?>)host.page(0,1).get("items")).getFirst(); }
        public void close() { if (responseGate != null) responseGate.countDown(); if (callbackGate != null) callbackGate.countDown(); host.close(); server.stop(0); executor.shutdownNow(); }
    }
}
