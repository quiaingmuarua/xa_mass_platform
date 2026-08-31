package com.xa.mass.integration.workerlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerStateConvergenceTest {

    @TempDir
    Path directory;

    @Test
    void uncontrolledWorkersAreNotAProofPreconditionOrMutationTarget()
            throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/lab/v1/workers", exchange -> {
            requests.add(exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getRawPath());
            respond(exchange, 200, Map.of("workers", inventoryWithExtraRun()));
        });
        server.start();
        try {
            URI base = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
            WorkerLabHarnessOptions options = new WorkerLabHarnessOptions(
                    base,
                    base,
                    "adapter-one",
                    "failed-state-world",
                    directory,
                    1_000,
                    500
            );

            assertThatThrownBy(() -> WorkerStateConvergence.execute(options))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(requests)
                    .hasSizeGreaterThan(1)
                    .allMatch(request -> request.startsWith("GET "));
        } finally {
            server.stop(0);
        }
    }

    private static List<Map<String, Object>> inventoryWithExtraRun() {
        List<Map<String, Object>> workers = new ArrayList<>();
        addGroup(workers, "scenario-phone-number-workers", "phone-number");
        addGroup(workers, "scenario-string-utils-workers", "string-utils");
        return workers;
    }

    private static void addGroup(
            List<Map<String, Object>> workers,
            String group,
            String capability
    ) {
        int workerCount = "phone-number".equals(capability) ? 3 : 2;
        for (int index = 1; index <= workerCount; index++) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("workerGroupId", group);
            snapshot.put(
                    "labWorkerKey",
                    "scenario-" + capability + "-worker-a.jsonl:" + index
            );
            snapshot.put("desiredState", "RUNNING");
            snapshot.put("runtimeState", "RUNNING");
            snapshot.put("workerId", "worker-" + index);
            snapshot.put("diagnosticMessage", null);
            snapshot.put("scheduledStopAtEpochMillis", null);
            workers.add(snapshot);
        }
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            Map<String, ?> value
    ) throws IOException {
        byte[] body = Jsons.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
