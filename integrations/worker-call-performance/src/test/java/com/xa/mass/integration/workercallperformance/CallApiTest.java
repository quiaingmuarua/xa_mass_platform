package com.xa.mass.integration.workercallperformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CallApiTest {
    @Test void callUsesFlatSelectorAndDoesNotRetryUnknownHttpOutcome() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            var body = Jsons.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var item = CallApi.object(((List<?>) body.get("items")).getFirst());
            assertThat(item.get("workerSelector")).isEqualTo(List.of("workerId", "$eq", "worker-1"));
            count.incrementAndGet();
            exchange.sendResponseHeaders(503, -1); exchange.close();
        });
        server.start();
        try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused")) {
            assertThat(api.call("task", "id", "worker-1")).isEqualTo(new CallLoad.Reply(503, CallLoad.Outcome.UNKNOWN));
            assertThat(count).hasValue(1);
        } finally { server.stop(0); }
    }

    @Test void followupObservationDoesNotRewriteOriginalCallOutcomeOrLatency() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] result = Jsons.toJson(Map.of("late-0", Map.of("status", "succeeded", "opaqueResultPayload",
                    Jsons.toJson(Map.of("input", CallApi.INPUT, "valid", true, "md5", "c1bb4f81d892b2d57947682aeb252456")))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, result.length);
            exchange.getResponseBody().write(result); exchange.close();
        });
        server.start();
        var clock = new AtomicLong(1_000_000_000L);
        var batch = CallLoad.schedule(1, 1, 1, "late", Runnable::run,
                (id, index) -> new CallLoad.Reply(200, CallLoad.Outcome.NOT_OBSERVED), clock::get, clock::set);
        long ended = batch.samples().getFirst().ended;
        try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused")) {
            WorkerCallPerformanceMain.settle(api, "task", batch, 1);
            WorkerCallPerformanceMain.requireHealthy(batch);
            assertThat(batch.samples().getFirst().outcome).isEqualTo(CallLoad.Outcome.NOT_OBSERVED);
            assertThat(batch.samples().getFirst().observed).isEqualTo("succeeded");
            assertThat(batch.samples().getFirst().ended).isEqualTo(ended);
            assertThat(batch.summary()).containsEntry("successRate", 0.0);
        } finally { server.stop(0); }
    }

    @Test void failedAndNotObservedCannotCarryPayloadAndUnknownStatusFailsClosed() {
        for (String status : List.of("failed", "not_observed")) {
            assertThat(CallApi.resultStatus(Map.of("status", status))).isEqualTo(status);
            assertThatThrownBy(() -> CallApi.resultStatus(Map.of("status", status, "opaqueResultPayload", "x")))
                    .isInstanceOf(CallLoad.ProtocolFailure.class);
        }
        assertThatThrownBy(() -> CallApi.resultStatus(Map.of("status", "done"))).isInstanceOf(CallLoad.ProtocolFailure.class);
    }

    @Test void successfulHttpStatusCannotHideWrongHandlerResult() {
        assertThatThrownBy(() -> CallApi.checkedResult(Map.of("status", "succeeded", "opaqueResultPayload", "{}"),
                CallApi.ExpectedResult.MD5)).isInstanceOf(CallLoad.ProtocolFailure.class);
        assertThat(CallApi.checkedResult(Map.of("status", "succeeded", "opaqueResultPayload", "null"),
                CallApi.ExpectedResult.DELAY)).isEqualTo("succeeded");
    }

    @Test void observationUsesItsRemainingDeadlineAndRejectsOversizedPagesBeforeSending() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { Thread.sleep(200); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            exchange.close();
        });
        server.start();
        try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused")) {
            assertThatThrownBy(() -> api.results("task", List.of("id"), CallApi.ExpectedResult.MD5,
                    java.time.Duration.ofMillis(20))).isInstanceOf(java.net.http.HttpTimeoutException.class);
            assertThatThrownBy(() -> api.results("task", java.util.Collections.nCopies(1001, "id")))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally { server.stop(0); }
    }
}
