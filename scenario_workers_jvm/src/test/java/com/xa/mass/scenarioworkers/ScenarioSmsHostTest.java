package com.xa.mass.scenarioworkers;

import com.xa.mass.scenarioworkers.sms.ListeningRegistry;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScenarioSmsHostTest {
    @Test void occupiedControlPortFailsBeforeAnyWorkerPreparation() throws Exception {
        try (var occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThatThrownBy(() -> ScenarioWorkerHostMain.main(new String[]{
                    "--scenario=sms", "--sms-counts=1,1,1", "--runtime-api-base-url=http://127.0.0.1:1",
                    "--control-port=" + occupied.getLocalPort()
            })).isInstanceOf(java.net.BindException.class);
        }
    }

    @Test void failedControlConstructionReleasesItsBoundPort() throws Exception {
        int port;
        try (var reserved = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = reserved.getLocalPort();
        }
        assertThatThrownBy(() -> ScenarioWorkerControlServer.open(port, null, null))
                .isInstanceOf(NullPointerException.class);
        try (var rebound = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThat(rebound.isBound()).isTrue();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Map<String, JavaWorkerManager> managers = new LinkedHashMap<>();
        final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
        final Map<String, ListeningRegistry.Sim> sims = new HashMap<>();
        final AtomicReference<ScenarioWorkers> owner = new AtomicReference<>();
        final ScenarioWorkers workers;
        Fixture(int firstCount) {
            workers = new ScenarioWorkers(URI.create("http://127.0.0.1:1"), new int[]{firstCount, 1, 1}, (uri, group) -> {
                String groupId = group.group().config().workerGroupId();
                var manager = mock(JavaWorkerManager.class);
                managers.put(groupId, manager);
                var states = new LinkedHashMap<String, AtomicBoolean>();
                for (var replica : group.replicas()) {
                    String key = replica.replicaKey();
                    var active = new AtomicBoolean();
                    states.put(key, active); running.put(key, active);
                    var properties = replica.properties();
                    var sim = owner.get().smsScenario().registry.addSim(groupId, key,
                            properties.get("phone"), properties.get("country"), () -> groupId + ":" + key,
                            () -> active.get() ? "RUNNING" : "STOPPED", active::get);
                    sims.put(key, sim);
                }
                when(manager.snapshot(anyString())).thenAnswer(call -> new WorkerLifecycle.Snapshot(
                        states.get(call.<String>getArgument(0)).get() ? WorkerLifecycle.State.RUNNING : WorkerLifecycle.State.STOPPED,
                        groupId + ":" + call.getArgument(0), null, null));
                when(manager.snapshots()).thenAnswer(call -> {
                    var snapshots = new LinkedHashMap<String, WorkerLifecycle.Snapshot>();
                    states.keySet().forEach(key -> snapshots.put(key, manager.snapshot(key)));
                    return snapshots;
                });
                when(manager.desiredRunning(anyString())).thenAnswer(call -> states.get(call.<String>getArgument(0)).get());
                doAnswer(call -> { states.values().forEach(active -> active.set(true)); return null; }).when(manager).start();
                doAnswer(call -> { states.get(call.<String>getArgument(0)).set(true); return null; }).when(manager).start(anyString());
                doAnswer(call -> { states.get(call.<String>getArgument(0)).set(false); return null; }).when(manager).stop(anyString());
                return manager;
            });
            owner.set(workers);
            workers.start();
        }
        @Override public void close() { workers.close(); }
    }

    @Test void smsSharesFiniteManagersAndHasOnlyReadOnlyInventoryAndExplicitControls() throws Exception {
        try (var fixture = new Fixture(101);
             var server = ScenarioWorkerControlServer.open(0, fixture.workers, null);
             var http = HttpClient.newHttpClient()) {
            server.start();
            assertThat(fixture.managers).hasSize(3);
            assertThat(fixture.workers.smsHealth()).containsEntry("numbers", 103).containsEntry("prepared", 103L);
            var page = get(http, server.baseUri(), "/lab/v1/sms/inventory?offset=100&limit=1");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat((List<?>) Jsons.parseObject(page.body()).get("items")).hasSize(1);
            assertThat(get(http, server.baseUri(), "/lab/v1/sms/inventory?limit=1001").statusCode()).isEqualTo(400);
            for (String path : List.of("/health", "/inventory", "/metrics", "/records", "/", "/lab/v1/workers", "/lab/v1/execution-witnesses"))
                assertThat(get(http, server.baseUri(), path).statusCode()).isEqualTo(404);
            assertThat(get(http, server.baseUri(), "/lab").body()).contains("data-scenario=\"sms\"");
            assertThat(post(http, server.baseUri(), "/lab/v1/sms/workers/sms-cn/CN-0:stop").statusCode()).isEqualTo(202);
            assertThat(fixture.running.get("CN-0")).isFalse();
            assertThat(fixture.running.get("CN-1")).isTrue();
            assertThat(post(http, server.baseUri(), "/lab/v1/sms/workers/sms-cn/CN-0:start").statusCode()).isEqualTo(202);
            assertThat(fixture.running.get("CN-0")).isTrue();
            assertThat(post(http, server.baseUri(), "/lab/v1/sms/workers/sms-cn/CN-0:properties").statusCode()).isEqualTo(400);
            assertThat(post(http, server.baseUri(), "/lab/v1/sms/workers/sms-cn/missing:stop").statusCode()).isEqualTo(404);
            for (var manager : fixture.managers.values()) verify(manager, never()).prepareAndStart(anyCollection());
        }
    }

    @Test void stopOvertakesSlowStartAndNoRestartLoopIsCreated() throws Exception {
        try (var fixture = new Fixture(1); var executor = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var manager = fixture.managers.get("sms-cn");
            fixture.workers.stopWorker("sms-cn", "CN-0");
            doAnswer(call -> {
                entered.countDown(); release.await(2, TimeUnit.SECONDS);
                fixture.running.get("CN-0").set(true); return null;
            }).when(manager).start("CN-0");
            var start = executor.submit(() -> fixture.workers.startWorker("sms-cn", "CN-0"));
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> fixture.workers.stopWorker("sms-cn", "CN-0")).get(1, TimeUnit.SECONDS);
                assertThatThrownBy(() -> fixture.workers.startWorker("sms-cn", "CN-0")).isInstanceOf(IllegalStateException.class);
                release.countDown(); start.get(1, TimeUnit.SECONDS);
                assertThat(fixture.running.get("CN-0")).isFalse();
                verify(manager, times(1)).start("CN-0");
            } finally { release.countDown(); }
        }
    }

    @Test void failedStartClosesAdmissionAndExistingManagersCloseOnceAfterPartialAssemblyFailure() {
        try (var fixture = new Fixture(1)) {
            doThrow(new IllegalStateException("start failed")).when(fixture.managers.get("sms-cn")).start("CN-0");
            assertThatThrownBy(() -> fixture.workers.startWorker("sms-cn", "CN-0")).hasMessage("start failed");
            assertThat(fixture.workers.smsScenario().registry.startStillRequested("+861700000000")).isFalse();
        }
        var first = mock(JavaWorkerManager.class);
        var creations = new AtomicInteger();
        var workers = new ScenarioWorkers(URI.create("http://127.0.0.1:1"), new int[]{1, 1, 1}, (uri, group) -> {
            if (creations.getAndIncrement() == 0) return first;
            throw new IllegalStateException("construction failed");
        });
        assertThatThrownBy(workers::start).isInstanceOf(ScenarioWorkerAssemblyException.class);
        workers.close(); workers.close();
        verify(first, times(1)).close();
        verify(first, never()).start();
    }

    private static HttpResponse<String> get(HttpClient http, URI base, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(HttpClient http, URI base, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }
}
