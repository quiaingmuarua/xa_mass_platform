package com.xa.mass.integration.androidworkerproof;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AndroidConvergenceTerminalTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void terminalPhaseObservesStoppedHostWithoutPreparingOrReadingRuntime() throws Exception {
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer runtime = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger runtimeRequests = new AtomicInteger();
        runtime.createContext("/", exchange -> {
            runtimeRequests.incrementAndGet();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        host.createContext("/", exchange -> {
            Object response = "/health".equals(exchange.getRequestURI().getPath())
                    ? Map.of("status", "ok")
                    : Map.of("status", "succeeded",
                            "eventCode", AndroidWorkerProofConstants.HOST_SNAPSHOT_EVENT,
                            "diagnosticCode", "200",
                            "result", Map.of("state", "STOPPED", "workerId", "worker-1",
                                    "processedCommands", 0, "activeDelayCount", 0,
                                    "diagnosticMessage", "endpoint budget exhausted"));
            byte[] bytes = Jsons.toJson(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        host.start();
        runtime.start();
        try {
            Path baselinePath = temporaryDirectory.resolve("active.json");
            var baselineOptions = AndroidWorkerProofOptions.parse(new String[]{
                    "--phase=active", "--proof-id=terminal-no-runtime",
                    "--evidence-file=" + baselinePath
            });
            var baseline = new ProofEvidence(baselineOptions,
                    AndroidWorkerConvergenceHealth.SCENARIO,
                    AndroidWorkerConvergenceHealth.ACTIVE_PHASE);
            baseline.workerId("worker-1");
            baseline.write();

            var options = AndroidWorkerProofOptions.parse(new String[]{
                    "--phase=terminal", "--proof-id=terminal-no-runtime",
                    "--server-base-url=http://127.0.0.1:" + runtime.getAddress().getPort(),
                    "--device-base-url=http://127.0.0.1:" + host.getAddress().getPort(),
                    "--baseline-file=" + baselinePath,
                    "--evidence-file=" + temporaryDirectory.resolve("terminal.json")
            });
            AndroidWorkerConvergenceHealth.execute(options);
            assertEquals(0, runtimeRequests.get());
        } finally {
            host.stop(0);
            runtime.stop(0);
        }
    }
}
