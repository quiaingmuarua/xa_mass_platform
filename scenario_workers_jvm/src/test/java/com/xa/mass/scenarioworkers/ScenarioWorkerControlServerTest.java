package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScenarioWorkerControlServerTest {

    private static final String GROUP = "scenario-group";
    private static final String INVENTORY = "client-1.jsonl";
    private static final String CLIENT = INVENTORY + ":1";

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
        List<String> records = new java.util.ArrayList<>();
        for (int line = 1; line <= 100; line++) {
            records.add(Jsons.toJson(Map.of(
                        "schemaVersion", 2,
                        "workerProperties", Map.of(
                                "labInventoryKey", INVENTORY,
                                "labInventoryLine", line,
                                "labSlot", line
                        )
            )));
        }
        Files.writeString(
                group.resolve(INVENTORY),
                String.join("\n", records) + "\n",
                StandardCharsets.UTF_8
        );

        manager = mock(JavaWorkerManager.class);
        when(manager.snapshot(anyString())).thenReturn(
                new WorkerLifecycle.Snapshot(
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
        verify(manager).prepareAndStart(List.of(CLIENT));
        verify(manager).stop(CLIENT);

        String replacement = Jsons.toJson(Map.of(
                "schemaVersion", 2,
                "workerProperties", Map.of(
                        "labInventoryKey", INVENTORY,
                        "labInventoryLine", 1,
                        "labSlot", 41
                )
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
                .prepareAndStart(List.of(CLIENT));

        HttpResponse<String> response = request(
                "POST",
                workerPath() + ":start",
                null
        );

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.body()).contains("state_conflict");
    }

    @Test
    void slowPrepareDoesNotBlockAStopRequestForTheSameWorker()
            throws Exception {
        CountDownLatch prepareEntered = new CountDownLatch(1);
        CountDownLatch releasePrepare = new CountDownLatch(1);
        doAnswer(invocation -> {
            prepareEntered.countDown();
            if (!releasePrepare.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Prepare was not released");
            }
            return null;
        }).when(manager).prepareAndStart(List.of(CLIENT));

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> starting = caller.submit(() ->
                    request("POST", workerPath() + ":start", null));
            assertThat(prepareEntered.await(1, TimeUnit.SECONDS)).isTrue();

            HttpResponse<String> stopped = request(
                    "POST",
                    workerPath() + ":stop",
                    null
            );
            assertThat(stopped.statusCode()).isEqualTo(202);
            verify(manager).stop(CLIENT);

            releasePrepare.countDown();
            assertThat(starting.get(1, TimeUnit.SECONDS).statusCode())
                    .isEqualTo(202);
        } finally {
            releasePrepare.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void batchStopAcceptsOneAndOneHundredCoordinates() throws Exception {
        HttpResponse<String> one = request(
                "POST",
                "/lab/v1/workers:stop",
                batchStopBody(1)
        );

        assertThat(one.statusCode()).isEqualTo(202);
        assertThat(one.body()).contains("\"acceptedCount\":1");
        verify(manager).stop(CLIENT);

        clearInvocations(manager);
        HttpResponse<String> hundred = request(
                "POST",
                "/lab/v1/workers:stop",
                batchStopBody(100)
        );

        assertThat(hundred.statusCode()).isEqualTo(202);
        assertThat(hundred.body()).contains("\"acceptedCount\":100");
        verify(manager, times(100)).stop(anyString());
    }

    @Test
    void batchStopValidatesEveryCoordinateBeforeMutation() throws Exception {
        String duplicate = Jsons.toJson(List.of(
                coordinate(CLIENT),
                coordinate(CLIENT)
        ));
        assertThat(request(
                "POST",
                "/lab/v1/workers:stop",
                duplicate
        ).statusCode()).isEqualTo(400);

        assertThat(request(
                "POST",
                "/lab/v1/workers:stop",
                batchStopBody(101)
        ).statusCode()).isEqualTo(400);

        String unknown = Jsons.toJson(List.of(
                coordinate(CLIENT),
                coordinate("missing.jsonl:1")
        ));
        assertThat(request(
                "POST",
                "/lab/v1/workers:stop",
                unknown
        ).statusCode()).isEqualTo(404);
        verify(manager, never()).stop(anyString());
    }

    @Test
    void batchStopDoesNotHoldTheInventoryGateDuringManagerCalls()
            throws Exception {
        CountDownLatch stopEntered = new CountDownLatch(1);
        CountDownLatch releaseStop = new CountDownLatch(1);
        doAnswer(invocation -> {
            stopEntered.countDown();
            if (!releaseStop.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Stop was not released");
            }
            return null;
        }).when(manager).stop(CLIENT);

        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<String>> stopping = caller.submit(() ->
                    request(
                            "POST",
                            "/lab/v1/workers:stop",
                            batchStopBody(1)
                    ));
            assertThat(stopEntered.await(1, TimeUnit.SECONDS)).isTrue();

            assertThat(request("GET", workerPath(), null).statusCode())
                    .isEqualTo(200);

            releaseStop.countDown();
            assertThat(stopping.get(1, TimeUnit.SECONDS).statusCode())
                    .isEqualTo(202);
        } finally {
            releaseStop.countDown();
            caller.shutdownNow();
        }
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

    private static String batchStopBody(int count) {
        List<Map<String, Object>> coordinates = new java.util.ArrayList<>();
        for (int line = 1; line <= count; line++) {
            coordinates.add(coordinate(INVENTORY + ":" + line));
        }
        return Jsons.toJson(coordinates);
    }

    private static Map<String, Object> coordinate(String labWorkerKey) {
        return Map.of(
                "workerGroupId", GROUP,
                "labWorkerKey", labWorkerKey
        );
    }
}
