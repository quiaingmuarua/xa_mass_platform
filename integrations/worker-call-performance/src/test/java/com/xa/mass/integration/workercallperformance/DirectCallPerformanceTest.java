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

class DirectCallPerformanceTest {
    @Test void directStatusesRemainDistinctWithoutInventingTaskClosure() {
        assertThat(reply("unobserved", "timeout").outcome()).isEqualTo(CallLoad.Outcome.TIMED_OUT);
        assertThat(reply("unobserved", "submission-unknown").outcome()).isEqualTo(CallLoad.Outcome.UNKNOWN);
        assertThat(reply("rejected", "command-slot-occupied").outcome()).isEqualTo(CallLoad.Outcome.REJECTED);
        for (String reason : List.of("shutdown", "not-found", "not-bound", "endpoint-mismatch"))
            assertThatThrownBy(() -> reply("rejected", reason)).isInstanceOf(CallLoad.ProtocolFailure.class);
    }

    @Test void observedFailureCannotCountAsSuccessAndWrongTargetOrPayloadFails() {
        var good = observed("200", Jsons.toJson(Map.of("input", CallApi.INPUT, "valid", true,
                "md5", "c1bb4f81d892b2d57947682aeb252456")));
        assertThat(CallApi.checkedDirectResult(good, "worker").outcome()).isEqualTo(CallLoad.Outcome.SUCCEEDED);
        assertThat(CallApi.checkedDirectResult(observed("23002", "opaque"), "worker").outcome()).isEqualTo(CallLoad.Outcome.FAILED);
        assertThatThrownBy(() -> CallApi.checkedDirectResult(good, "another-worker")).isInstanceOf(CallLoad.ProtocolFailure.class);
        assertThatThrownBy(() -> CallApi.checkedDirectResult(observed("200", "{}"), "worker"))
                .isInstanceOf(CallLoad.ProtocolFailure.class);
        assertThatThrownBy(() -> CallApi.checkedDirectResult(Map.of("directCallId", "call", "status", "observed",
                "results", Map.of("worker", Map.of("status", "unobserved", "reason", "timeout"))), "worker"))
                .isInstanceOf(CallLoad.ProtocolFailure.class);
    }

    @Test void directApiUsesOneExplicitWorkerAndDoesNotRetry429OrUnknown503() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            assertThat(exchange.getRequestURI().getPath()).endsWith("/endpoint-managers/scenario-websocket/direct-calls");
            var body = Jsons.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            assertThat(body.keySet()).containsExactlyInAnyOrder("workerGroupId", "workerPayloads", "messageType", "waitTimeoutMillis");
            assertThat(body.get("workerGroupId")).isEqualTo(CallApi.GROUP);
            assertThat(CallApi.object(body.get("workerPayloads"))).containsOnlyKeys("worker");
            assertThat(Jsons.parseObject((String) CallApi.object(body.get("workerPayloads")).get("worker")))
                    .containsEntry("value", CallApi.INPUT);
            int count = requests.incrementAndGet();
            exchange.sendResponseHeaders(count == 1 ? 429 : 503, -1);
            exchange.close();
        });
        server.start();
        try (var api = new CallApi("http://127.0.0.1:" + server.getAddress().getPort(), "unused")) {
            assertThat(api.directCall("worker").outcome()).isEqualTo(CallLoad.Outcome.REJECTED);
            assertThat(api.directCall("worker").outcome()).isEqualTo(CallLoad.Outcome.UNKNOWN);
            assertThat(requests).hasValue(2);
        } finally { server.stop(0); }
    }

    @Test void deadlineFractionIncludesSlowSuccessWhileRejectionAndTimeoutStayInDenominator() throws Exception {
        var clock = new AtomicLong(1_000_000_000L);
        var batch = CallLoad.schedule(4, 1, 4, "direct", Runnable::run, (id, index) -> {
            if (index == 0) clock.addAndGet(10_000_000);
            if (index == 1) clock.addAndGet(1_100_000_000);
            return new CallLoad.Reply(200, switch (index) {
                case 0, 1 -> CallLoad.Outcome.SUCCEEDED;
                case 2 -> CallLoad.Outcome.REJECTED;
                default -> CallLoad.Outcome.TIMED_OUT;
            });
        }, clock::get, deadline -> clock.updateAndGet(now -> Math.max(now, deadline)));
        batch.await();
        DirectCallPerformance.requireValid(batch);
        var summary = DirectCallPerformance.summarize(batch);
        assertThat(summary).containsEntry("successRate", .5).containsEntry("withinOneSecondRateOfSent", .25)
                .containsEntry("withinOneSecondRateOfPlanned", .25).containsEntry("http200", 4L);
        assertThat(summary).doesNotContainKeys("accepted", "resultCounts", "acceptedSuccessRateAfterDrain", "unresolvedAcceptedIds");
        assertThat(CallApi.object(summary.get("outcomes")).values().stream().mapToLong(n -> ((Number) n).longValue()).sum()).isEqualTo(4);
    }

    @Test void fixedHighestRateHasAFiniteBudgetAndUnknownExecutionDoesNotFailMeasurement() {
        assertThat(DirectCallPerformance.CASES.get("direct-5000") * 30).isEqualTo(150_000);
        assertThatThrownBy(() -> CallLoad.schedule(5_001, 30, 4_096, "large", Runnable::run,
                (id, index) -> new CallLoad.Reply(200, CallLoad.Outcome.SUCCEEDED))).isInstanceOf(IllegalArgumentException.class);
        var clock = new AtomicLong(1_000_000_000L);
        var batch = CallLoad.schedule(1, 1, 1, "unknown", Runnable::run,
                (id, index) -> new CallLoad.Reply(503, CallLoad.Outcome.UNKNOWN), clock::get, clock::set);
        DirectCallPerformance.requireValid(batch);
        assertThat(DirectCallPerformance.summarize(batch)).containsEntry("successRate", 0.0);
    }

    private static CallLoad.Reply reply(String state, String reason) {
        return CallApi.checkedDirectResult(Map.of("directCallId", "call", "status", "partial",
                "results", Map.of("worker", Map.of("status", state, "reason", reason))), "worker");
    }

    private static Map<String, Object> observed(String code, String payload) {
        return Map.of("directCallId", "call", "status", "observed", "results",
                Map.of("worker", Map.of("status", "observed", "outcomeCode", code, "opaqueResultPayload", payload)));
    }
}
