package com.xa.mass.integration.workerlab;

import static com.xa.mass.integration.workerlab.WorkerLabConvergenceSupport.CONVERGENCE_WORKERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkerTaskFaultConvergenceTest {

    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false"})
    void hostDownRequiresRealDisconnectionButNotUnusedHotStock(boolean readable, boolean disconnected)
            throws Exception {
        var networkReads = new AtomicInteger();
        var scoreReads = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/runtime-view", exchange -> {
            boolean network = exchange.getRequestURI().getPath().endsWith(":network-observe");
            (network ? networkReads : scoreReads).incrementAndGet();
            var states = new LinkedHashMap<String, String>();
            for (Object id : Jsons.parseArray(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8))) {
                states.put((String) id, network ? (disconnected ? "disconnected" : "connected") : "held-hot");
            }
            byte[] body = Jsons.toJson(Map.of("statesByWorkerId", states))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(network || readable ? 200 : 503, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var options = new WorkerLabHarnessOptions(base, base, "adapter", "proof-down",
                    directory, 1_000, 1_000);
            var identities = new LinkedHashMap<String, String>();
            CONVERGENCE_WORKERS.forEach(worker -> identities.put(worker.coordinate(), worker.coordinate()));
            Path stateFile = directory.resolve("phase-state.json");
            new TaskFaultState(options.proofId(), Instant.now(), identities,
                    WorkerTaskFaultConvergence.TARGET.coordinate(),
                    WorkerTaskFaultConvergence.TARGET.coordinate(),
                    WorkerTaskFaultConvergence.BACKUP.coordinate(), "checkpoint", "item",
                    ConvergenceTestData.batches(2), null).save(stateFile);

            if (!disconnected) {
                assertThatThrownBy(() -> WorkerTaskFaultConvergence.observeDown(options, stateFile))
                        .isInstanceOf(IllegalStateException.class).hasMessageContaining("host-down-network");
                assertThat(scoreReads).hasValue(0);
                return;
            }
            WorkerTaskFaultConvergence.observeDown(options, stateFile);

            assertThat(networkReads).hasValue(10);
            // One bounded sample per Group, with no waiting or repeat on HOT / read failure.
            assertThat(scoreReads).hasValue(readable ? 10 : 2);
            String timeline = Files.readString(directory.resolve(
                    ConvergenceEvidence.timelineFileName(WorkerTaskFaultConvergence.LANE)));
            assertThat(timeline).contains("worker-world-disconnected");
            assertThat(timeline).contains(readable ? "\"held-hot\":500" : "scheduling-sample-unavailable");
            assertThat(Files.exists(directory.resolve(
                    ConvergenceEvidence.summaryFileName(WorkerTaskFaultConvergence.LANE)))).isFalse();
        } finally {
            server.stop(0);
        }
    }
}
