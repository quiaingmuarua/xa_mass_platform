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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerLabReliabilityTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void failedInitialWorkersPrecheckDoesNotMutateTheLab() throws Exception {
        List<String> requests = Collections.synchronizedList(
                new ArrayList<>()
        );
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/lab/v1/workers", exchange -> {
            requests.add(exchange.getRequestMethod() + " "
                    + exchange.getRequestURI().getRawPath());
            respond(exchange, 200, Map.of("workers", runningInventory()));
        });
        server.start();
        try {
            URI labBase = URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
            WorkerLabReliabilityMain.Options options =
                    new WorkerLabReliabilityMain.Options(
                            labBase,
                            labBase,
                            "adapter-1",
                            "failed-precheck",
                            temporaryDirectory,
                            1_000,
                            500,
                            10
                    );

            assertThatThrownBy(() -> WorkerLabReliability.execute(options))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("initial-workers=none");

            assertThat(requests).containsExactly("GET /lab/v1/workers");
        } finally {
            server.stop(0);
        }
    }

    private static List<Map<String, Object>> runningInventory() {
        List<Map<String, Object>> workers = new ArrayList<>();
        for (int index = 1; index <= 20; index++) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("workerGroupId", "group-" + index);
            snapshot.put("clientWorkerKey", "worker-" + index);
            snapshot.put("desiredState", "RUNNING");
            snapshot.put("runtimeState", "RUNNING");
            snapshot.put("workerId", "worker-id-" + index);
            snapshot.put("diagnosticMessage", null);
            snapshot.put("scheduledStopAtEpochMillis", null);
            workers.add(snapshot);
        }
        return workers;
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            Map<String, ?> value
    ) throws IOException {
        byte[] body = Jsons.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
