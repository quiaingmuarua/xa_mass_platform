package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerControlServerTest {

    private static final String GROUP = "scenario-group";
    private static final String CLIENT = "client-1";

    @TempDir
    Path temporaryDirectory;

    private JavaWorkerManager manager;
    private ScenarioWorkers workers;
    private ScenarioWorkerScheduledStops scheduledStops;
    private ScenarioWorkerControlServer server;
    private HttpClient http;

    @BeforeEach
    void startControlServer() throws Exception {
        temporaryDirectory = temporaryDirectory.toRealPath();
        Path root = temporaryDirectory.resolve("data/scenario-workers");
        Path group = root.resolve(GROUP);
        Files.createDirectories(group);
        Files.writeString(
                group.resolve(CLIENT + ".json"),
                Jsons.toJson(Map.of(
                        "schemaVersion", 2,
                        "workerProperties", Map.of("labSlot", 1)
                )),
                StandardCharsets.UTF_8
        );

        manager = mock(JavaWorkerManager.class);
        when(manager.snapshot(CLIENT)).thenReturn(new WorkerLifecycle.Snapshot(
                WorkerLifecycle.State.STOPPED,
                "worker-1",
                null,
                null
        ));
        ScenarioWorkerCommandCheckpoints checkpoints =
                new ScenarioWorkerCommandCheckpoints();
        WorkerEventDefinition<?> definition =
                StringUtilityWorkerEvents.definitions().get(0);
        WorkerEventDefinition<?> checkpointDefinition =
                ScenarioWorkerLabEvents.checkpoint(checkpoints);
        workers = new ScenarioWorkers(
                URI.create("http://127.0.0.1:18082"),
                root.toString(),
                ScenarioWorkersJsonParser.parse(Jsons.toJson(Map.of(
                        GROUP,
                        Map.of("eventCodes", List.of(
                                definition.eventName(),
                                checkpointDefinition.eventName()
                        ))
                ))),
                Map.of(
                        definition.eventName(), definition,
                        checkpointDefinition.eventName(), checkpointDefinition
                ),
                (runtimeApiBaseUrl, preparedGroup) -> manager,
                checkpoints
        );
        workers.start(ScenarioWorkerStartupPlan.parse("""
                {
                  "schemaVersion":1,
                  "initialWorkers":[],
                  "scheduledStops":[]
                }
                """));
        scheduledStops = new ScenarioWorkerScheduledStops(workers);
        server = ScenarioWorkerControlServer.open(
                0,
                workers,
                scheduledStops
        );
        server.start();
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterEach
    void closeControlServer() {
        if (server != null) {
            server.close();
        }
        if (scheduledStops != null) {
            scheduledStops.close();
        }
        if (workers != null) {
            workers.close();
        }
    }

    @Test
    void servesSelfContainedLabConsoleOnExactRoutes() throws Exception {
        HttpResponse<String> console = request("GET", "/lab", null);

        assertThat(console.statusCode()).isEqualTo(200);
        assertThat(console.headers().firstValue("Content-Type"))
                .hasValue("text/html; charset=utf-8");
        assertThat(console.headers().firstValue("Cache-Control"))
                .hasValue("no-store");
        assertThat(console.headers().firstValue("X-Content-Type-Options"))
                .hasValue("nosniff");
        assertThat(console.headers().firstValue("Content-Security-Policy"))
                .hasValueSatisfying(value -> assertThat(value)
                        .contains("connect-src 'self'", "frame-ancestors 'none'"));
        assertThat(console.body())
                .contains(
                        "Scenario Worker Lab",
                        "/lab/v1/workers",
                        "Save properties",
                        "Schedule stop"
                )
                .doesNotContain(
                        "https://",
                        "http://",
                        "<script src=",
                        "<link rel=\"stylesheet\""
                );

        assertThat(request("GET", "/lab/", null).statusCode())
                .isEqualTo(200);
        HttpResponse<String> wrongMethod = request("POST", "/lab", null);
        assertThat(wrongMethod.statusCode()).isEqualTo(405);
        assertThat(wrongMethod.headers().firstValue("Allow"))
                .hasValue("GET");
        assertThat(request("GET", "/lab/not-found", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void exposesInventoryControlsAndAtomicStateReplacement()
            throws Exception {
        HttpResponse<String> list = request("GET", "/lab/v1/workers", null);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(CLIENT).doesNotContain(
                "workerProperties"
        );

        HttpResponse<String> single = request(
                "GET",
                workerPath(),
                null
        );
        assertThat(single.statusCode()).isEqualTo(200);
        assertThat(single.body()).contains("workerProperties", "labSlot");

        assertThat(request("POST", workerPath() + ":start", null)
                .statusCode()).isEqualTo(202);
        assertThat(request("POST", workerPath() + ":stop", null)
                .statusCode()).isEqualTo(202);
        verify(manager).start(CLIENT);
        verify(manager).stop(CLIENT);

        String replacement = Jsons.toJson(Map.of(
                "schemaVersion", 2,
                "workerProperties", Map.of("labSlot", 41)
        ));
        HttpResponse<String> replaced = request(
                "PUT",
                workerPath(),
                replacement
        );
        assertThat(replaced.statusCode()).isEqualTo(200);
        assertThat(replaced.body()).contains("41");

        assertThat(request(
                "GET",
                "/lab/v1/workers/" + GROUP + "/missing",
                null
        ).statusCode()).isEqualTo(404);
        assertThat(request(
                "GET",
                "/lab/v1/workers/" + GROUP + "/%2e%2e",
                null
        ).statusCode()).isEqualTo(400);
    }

    @Test
    void schedulesRejectsDuplicatesAndCancelsIdempotently()
            throws Exception {
        String body = Jsons.toJson(Map.of("delayMillis", 60_000));
        assertThat(request(
                "POST",
                workerPath() + ":schedule-stop",
                body
        ).statusCode()).isEqualTo(202);
        assertThat(request(
                "POST",
                workerPath() + ":schedule-stop",
                body
        ).statusCode()).isEqualTo(409);
        assertThat(request(
                "DELETE",
                workerPath() + ":scheduled-stop",
                null
        ).statusCode()).isEqualTo(204);
        assertThat(request(
                "DELETE",
                workerPath() + ":scheduled-stop",
                null
        ).statusCode()).isEqualTo(204);
    }

    @Test
    void startReportsConflictWhilePriorStopIsStillConverging()
            throws Exception {
        doThrow(new IllegalStateException("still stopping"))
                .when(manager)
                .start(CLIENT);

        HttpResponse<String> response = request(
                "POST",
                workerPath() + ":start",
                null
        );

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("state_conflict");
    }

    @Test
    void armsReadsAndReleasesCommandCheckpoint() throws Exception {
        String path = workerPath() + ":command-checkpoint";
        String body = Jsons.toJson(Map.of(
                "checkpointToken", "checkpoint-1",
                "maximumHoldMillis", 30_000
        ));

        HttpResponse<String> armed = request("PUT", path, body);
        assertThat(armed.statusCode()).isEqualTo(201);
        assertThat(armed.body()).contains(
                "checkpoint-1",
                "ARMED"
        );
        assertThat(request("PUT", path, body).statusCode()).isEqualTo(409);
        assertThat(request("GET", path, null).statusCode()).isEqualTo(200);
        assertThat(request("DELETE", path, null).statusCode())
                .isEqualTo(204);
        assertThat(request("GET", path, null).statusCode()).isEqualTo(404);
    }

    @Test
    void scheduledStopFiresOnceAndCancelledStopDoesNotFire()
            throws Exception {
        assertThat(scheduledStops.schedule(GROUP, CLIENT, 200)).isTrue();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (scheduledStops.scheduledStopAtEpochMillis(GROUP, CLIENT)
                != null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(scheduledStops.scheduledStopAtEpochMillis(GROUP, CLIENT))
                .isNull();
        verify(manager, times(1)).stop(CLIENT);

        assertThat(scheduledStops.schedule(GROUP, CLIENT, 500)).isTrue();
        assertThat(scheduledStops.cancel(GROUP, CLIENT)).isTrue();
        Thread.sleep(600);

        verify(manager, times(1)).stop(CLIENT);
    }

    private HttpResponse<String> request(
            String method,
            String path,
            String body
    ) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(
                        server.baseUri().resolve(path)
                )
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String workerPath() {
        return "/lab/v1/workers/" + GROUP + "/" + CLIENT;
    }
}
