package com.xa.mass.workersimulator;

import com.xa.mass.workersimulator.sms.ListeningRegistry;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkerSimulatorProductHostTest {
    @TempDir Path temp;
    private static final String FIRST = "workers-000.jsonl:1";
    private static final String SECOND = "workers-000.jsonl:2";
    @Test void occupiedControlPortFailsBeforeAnyWorkerPreparation() throws Exception {
        try (var occupied = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            Path config = temp.resolve("sim.json");
            Files.writeString(config, Jsons.toJson(Map.of("runtimeApiBaseUrl", "http://127.0.0.1:1",
                    "sandboxRoot", temp.resolve("data/scenario-workers").toString(),
                    "controlPort", occupied.getLocalPort(), "workerGroups", Map.of())));
            assertThatThrownBy(() -> WorkerSimulatorMain.main(new String[]{"--config", config.toString()}))
                    .isInstanceOf(java.net.BindException.class);
        }
    }

    @Test void failedControlConstructionReleasesItsBoundPort() throws Exception {
        int port;
        try (var reserved = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            port = reserved.getLocalPort();
        }
        assertThatThrownBy(() -> WorkerSimulatorControlServer.open(port, null, null))
                .isInstanceOf(NullPointerException.class);
        try (var rebound = new java.net.ServerSocket(port, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThat(rebound.isBound()).isTrue();
        }
    }

    private final class Fixture implements AutoCloseable {
        final Map<String, JavaWorkerManager> managers = new LinkedHashMap<>();
        final Map<String, AtomicBoolean> running = new ConcurrentHashMap<>();
        final Map<String, ListeningRegistry.Sim> sims = new HashMap<>();
        final Map<String, WorkerSimulator.PreparedReplica> replicas = new LinkedHashMap<>();
        final Path root;
        final AtomicReference<WorkerSimulator> owner = new AtomicReference<>();
        final WorkerSimulator workers;
        Fixture(int firstCount) { this(firstCount, temp.resolve("data/scenario-workers")); }
        Fixture(int firstCount, Path root) {
            this.root = root;
            var configs = SimulatorTestConfig.products(firstCount + 2);
            workers = new WorkerSimulator(URI.create("http://127.0.0.1:1"), root.toString(), 0, configs, Map.of(), (uri, group) -> {
                String groupId = group.group().config().workerGroupId();
                var manager = mock(JavaWorkerManager.class);
                managers.put(groupId, manager);
                var states = new LinkedHashMap<String, AtomicBoolean>();
                for (var replica : group.replicas()) {
                    String key = replica.replicaKey();
                    replicas.put(key, replica);
                    var active = new AtomicBoolean();
                    states.put(key, active); running.put(key, active);
                    var sim = owner.get().smsScenario().registry.addSim(groupId, key,
                            replica::properties, () -> groupId + ":" + key,
                            () -> active.get() ? "RUNNING" : "STOPPED", active::get);
                    replica.sim = sim;
                    replica.sender = owner.get().messageScenario().addSender(groupId, key, replica::properties,
                            () -> groupId + ":" + key, () -> active.get() ? "RUNNING" : "STOPPED");
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
                doAnswer(call -> { call.<Collection<String>>getArgument(0).forEach(key -> states.get(key).set(true)); return null; })
                        .when(manager).prepareAndStart(anyCollection());
                doAnswer(call -> { states.get(call.<String>getArgument(0)).set(false); return null; }).when(manager).stop(anyString());
                return manager;
            }, new WorkerSimulatorCommandCheckpoints(), new WorkerSimulatorExecutionWitnesses());
            owner.set(workers);
            workers.start();
        }
        @Override public void close() { workers.close(); }
    }

    @Test void productsShareFileBatchesAndAllCommonControls() throws Exception {
        try (var fixture = new Fixture(101);
             var stops = new WorkerSimulatorScheduledStops(fixture.workers);
             var server = WorkerSimulatorControlServer.open(0, fixture.workers, stops);
             var http = HttpClient.newHttpClient()) {
            server.start();
            assertThat(fixture.managers).hasSize(1);
            assertThat(fixture.workers.smsHealth()).containsEntry("numbers", 103).containsEntry("prepared", 103L);
            var page = get(http, server.baseUri(), "/lab/v1/sms/inventory?offset=100&limit=1");
            assertThat(page.statusCode()).isEqualTo(200);
            assertThat((List<?>) Jsons.parseObject(page.body()).get("items")).hasSize(1);
            assertThat(get(http, server.baseUri(), "/lab/v1/sms/inventory?limit=1001").statusCode()).isEqualTo(400);
            for (String path : List.of("/health", "/inventory", "/metrics", "/records", "/"))
                assertThat(get(http, server.baseUri(), path).statusCode()).isEqualTo(404);
            assertThat(get(http, server.baseUri(), "/lab").body()).contains("data-sms=\"true\"", "data-messages=\"true\"");
            assertThat(get(http, server.baseUri(), "/lab/v1/workers").statusCode()).isEqualTo(200);
            assertThat(post(http, server.baseUri(), "/lab/v1/sms/workers/demo-sim/" + FIRST + ":stop").statusCode()).isEqualTo(202);
            assertThat(fixture.running.get(FIRST)).isFalse();
            assertThat(fixture.running.get(SECOND)).isTrue();
            assertThat(post(http, server.baseUri(), "/lab/v1/sms/workers/demo-sim/" + FIRST + ":start").statusCode()).isEqualTo(202);
            assertThat(fixture.running.get(FIRST)).isTrue();
            assertThat(post(http, server.baseUri(), "/lab/v1/sms/workers/demo-sim/missing:stop").statusCode()).isEqualTo(404);
            var batches = org.mockito.ArgumentCaptor.forClass(Collection.class);
            verify(fixture.managers.get("demo-sim"), times(3)).prepareAndStart(batches.capture());
            assertThat(batches.getAllValues().stream().map(Collection::size).toList()).containsExactly(100, 3, 1);
            assertThat(Files.readAllLines(fixture.root.resolve("demo-sim/workers-000.jsonl"))).hasSize(100);
            assertThat(Files.readAllLines(fixture.root.resolve("demo-sim/workers-001.jsonl"))).hasSize(3);
        }
    }

    @Test void stopOvertakesSlowStartAndNoRestartLoopIsCreated() throws Exception {
        try (var fixture = new Fixture(1); var executor = Executors.newFixedThreadPool(2)) {
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            var manager = fixture.managers.get("demo-sim");
            fixture.workers.stopWorker("demo-sim", FIRST);
            doAnswer(call -> {
                entered.countDown(); release.await(2, TimeUnit.SECONDS);
                fixture.running.get(FIRST).set(true); return null;
            }).when(manager).prepareAndStart(List.of(FIRST));
            var start = executor.submit(() -> fixture.workers.startWorker("demo-sim", FIRST));
            try {
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                executor.submit(() -> fixture.workers.stopWorker("demo-sim", FIRST)).get(1, TimeUnit.SECONDS);
                assertThatThrownBy(() -> fixture.workers.startWorker("demo-sim", FIRST)).isInstanceOf(IllegalStateException.class);
                release.countDown(); start.get(1, TimeUnit.SECONDS);
                assertThat(fixture.running.get(FIRST)).isFalse();
                verify(manager, times(1)).prepareAndStart(List.of(FIRST));
            } finally { release.countDown(); }
        }
    }

    @Test void failedStartClosesAdmissionAndExistingManagersCloseOnceAfterPartialAssemblyFailure() {
        try (var fixture = new Fixture(1)) {
            doThrow(new IllegalStateException("start failed")).when(fixture.managers.get("demo-sim")).prepareAndStart(List.of(FIRST));
            assertThatThrownBy(() -> fixture.workers.startWorker("demo-sim", FIRST)).hasMessage("start failed");
            assertThat(fixture.workers.smsScenario().registry.startStillRequested(fixture.sims.get(FIRST))).isFalse();
        }
        var first = mock(JavaWorkerManager.class);
        doThrow(new IllegalStateException("start failed")).when(first).prepareAndStart(anyCollection());
        var workers = new WorkerSimulator(URI.create("http://127.0.0.1:1"), temp.resolve("other/data/scenario-workers").toString(),
                0, SimulatorTestConfig.products(3), Map.of(),
                (uri, group) -> first, new WorkerSimulatorCommandCheckpoints(), new WorkerSimulatorExecutionWitnesses());
        assertThatThrownBy(workers::start).isInstanceOf(WorkerSimulatorAssemblyException.class);
        workers.close(); workers.close();
        verify(first, times(1)).close();
        verify(first, times(1)).prepareAndStart(anyCollection());
    }

    @Test void hotPhoneAndCountryShareTheInventoryButPreserveBusinessHistory() throws Exception {
        try (var fixture = new Fixture(1)) {
            var worker = fixture.replicas.get(FIRST);
            var registry = fixture.workers.smsScenario().registry;
            var messageOwner = fixture.workers.messageScenario();
            var smsReports = new AtomicInteger();
            var messageReports = new ArrayList<Map<String, Object>>();
            var oldListen = listen("old", "CN");
            registry.listen(worker.sim, oldListen, (tag, time, payload) -> { smsReports.incrementAndGet(); return true; });
            var send = Map.<String, Object>of("campaignId", "c", "messageId", "old", "country", "CN", "recipientId", "r", "body", "body");
            messageOwner.send(worker.sender, send, (tag, time, payload) -> { messageReports.add(Jsons.parseObject(payload)); return true; });
            String oldPhone = worker.properties().get("phone");
            clearInvocations(fixture.managers.get("demo-sim"));
            assertThat(fixture.workers.simulateInput("demo-sim", FIRST, "properties.update", new LinkedHashMap<>(Map.of("phone", "+new", "country", "GB")))).containsEntry("sendAccepted", false);
            assertThat(worker.properties()).containsEntry("phone", "+new").containsEntry("country", "GB");
            assertThat(worker.stateFile().readProperties()).isEqualTo(worker.properties());
            assertThat(registry.listen(worker.sim, oldListen, (tag, time, payload) -> { throw new AssertionError("rebound"); }))
                    .containsEntry("status", "INTERRUPTED").containsEntry("phone", oldPhone).containsEntry("country", "CN");
            assertThat(smsReports).hasValue(0);
            assertThatThrownBy(() -> registry.receive(fixture.sims.get(FIRST), oldPhone, "stale", "text")).isInstanceOf(IllegalArgumentException.class);
            assertThat(messageOwner.send(worker.sender, send, (tag, time, payload) -> false)).containsEntry("country", "CN").containsEntry("phone", oldPhone);
            assertThat(messageOwner.act(fixture.replicas.get(FIRST).sender, "old", "deliver", Map.of())).containsEntry("sendAccepted", true);
            assertThat(messageReports).singleElement().satisfies(report -> assertThat(report).containsEntry("phone", oldPhone));
            registry.listen(worker.sim, listen("new", "GB"), (tag, time, payload) -> { smsReports.incrementAndGet(); return true; });
            assertThat(registry.receive(fixture.sims.get(FIRST), "+new", "new", "text")).containsEntry("status", "MATCHED");
            assertThat(smsReports).hasValue(1);
            verify(fixture.managers.get("demo-sim"), never()).prepareAndStart(anyCollection());
            verify(fixture.managers.get("demo-sim"), never()).stop(anyString());
        }
    }

    @Test void invalidConflictAndPersistenceFailureHaveNoBusinessSideEffects() throws Exception {
        try (var fixture = new Fixture(1)) {
            var worker = fixture.replicas.get(FIRST);
            var original = worker.properties();
            var registry = fixture.workers.smsScenario().registry;
            registry.listen(worker.sim, listen("old", "CN"), (tag, time, payload) -> true);
            for (Map<String, String> invalid : List.of(Map.of("phone", ""), Map.of("country", "cn"),
                    Map.of("phone", fixture.replicas.get(SECOND).properties().get("phone")))) {
                assertThatThrownBy(() -> fixture.workers.simulateInput("demo-sim", FIRST, "properties.update", new LinkedHashMap<>(invalid)))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            var file = fixture.root.resolve("demo-sim/workers-000.jsonl");
            var backup = file.resolveSibling("saved.jsonl");
            Files.move(file, backup);
            Files.createDirectory(file);
            try {
                assertThatThrownBy(() -> fixture.workers.simulateInput("demo-sim", FIRST, "properties.update", new LinkedHashMap<>(Map.of("phone", "changed"))))
                        .isInstanceOf(RuntimeException.class);
                assertThat(worker.properties()).isEqualTo(original);
                assertThat(registry.metrics()).containsEntry("activeListeners", 1);
            } finally { Files.delete(file); Files.move(backup, file); }
            assertThat(worker.stateFile().readProperties()).isEqualTo(original);
            assertThat(registry.receive(fixture.sims.get(FIRST), original.get("phone"), "still-old", "text")).containsEntry("status", "MATCHED");
        }
    }

    @Test void simultaneousPhoneClaimsOnlyCommitOneInventoryRecord() throws Exception {
        try (var fixture = new Fixture(1); var executor = Executors.newFixedThreadPool(2)) {
            var begin = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (String key : List.of(FIRST, SECOND)) results.add(executor.submit(() -> {
                begin.await();
                try { fixture.workers.simulateInput("demo-sim", key, "properties.update", new LinkedHashMap<>(Map.of("phone", "same"))); return true; }
                catch (IllegalArgumentException rejected) { return false; }
            }));
            begin.countDown();
            assertThat(List.of(results.get(0).get(2, TimeUnit.SECONDS), results.get(1).get(2, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(fixture.replicas.values().stream().filter(r -> r.properties().get("phone").equals("same")).count()).isEqualTo(1);
            for (var replica : fixture.replicas.values()) assertThat(replica.stateFile().readProperties()).isEqualTo(replica.properties());
        }
    }

    @Test void fileOnlyEditsReloadAndRestartKeepCoordinatesAndNeverReseed() throws Exception {
        Path root = temp.resolve("data/scenario-workers");
        Map<String, String> modified;
        try (var fixture = new Fixture(1, root)) {
            var replica = fixture.replicas.get(FIRST);
            modified = new LinkedHashMap<>(replica.properties()); modified.put("phone", "persisted");
            fixture.workers.replaceWorkerState("demo-sim", FIRST, Jsons.toJson(Map.of("schemaVersion", 2, "workerProperties", modified)));
            assertThat(replica.properties()).isEqualTo(modified);
            verify(fixture.managers.get("demo-sim"), never()).reportProperties(anyString());
            var records = Files.readAllLines(root.resolve("demo-sim/workers-000.jsonl"));
            modified.put("country", "US");
            records.set(0, Jsons.toJson(Map.of("schemaVersion", 2, "workerProperties", modified)));
            Files.write(root.resolve("demo-sim/workers-000.jsonl"), records);
            assertThat(replica.properties().get("country")).isEqualTo("CN");
            fixture.workers.stopWorker("demo-sim", FIRST);
            fixture.workers.startWorker("demo-sim", FIRST);
            assertThat(replica.properties()).isEqualTo(modified);
        }
        try (var restarted = new Fixture(101, root)) {
            assertThat(restarted.replicas).hasSize(3);
            assertThat(restarted.replicas.get(FIRST).properties()).isEqualTo(modified);
            assertThat(restarted.workers.workerSnapshot("demo-sim", FIRST, true).runtime().workerId()).isEqualTo("demo-sim:" + FIRST);
        }
        Path empty = temp.resolve("empty/data/scenario-workers");
        Files.createDirectories(empty.resolve("demo-sim"));
        try (var fixture = new Fixture(20, empty)) {
            assertThat(fixture.replicas).isEmpty();
            assertThat(fixture.managers).isEmpty();
            assertThat(fixture.workers.initialWorkerCount()).isZero();
        }
    }

    @Test void batchAndScheduledStopClearReportersBeforeSdkStop() throws Exception {
        try (var fixture = new Fixture(1); var stops = new WorkerSimulatorScheduledStops(fixture.workers)) {
            var registry = fixture.workers.smsScenario().registry;
            registry.listen(fixture.replicas.get(FIRST).sim, listen("a", "CN"), (tag, time, payload) -> { throw new AssertionError("stopped reporter"); });
            registry.listen(fixture.replicas.get(SECOND).sim, listen("b", "US"), (tag, time, payload) -> { throw new AssertionError("stopped reporter"); });
            var manager = fixture.managers.get("demo-sim");
            doAnswer(call -> {
                String key = call.getArgument(0);
                assertThat(registry.startStillRequested(fixture.replicas.get(key).sim)).isFalse();
                fixture.running.get(key).set(false); return null;
            }).when(manager).stop(anyString());
            fixture.workers.stopWorkers(List.of(new WorkerSimulatorCoordinate("demo-sim", FIRST)));
            assertThat(stops.schedule("demo-sim", SECOND, 1)).isTrue();
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (fixture.running.get(SECOND).get() && System.nanoTime() < end) Thread.sleep(5);
            assertThat(fixture.running.get(SECOND)).isFalse();
            assertThat(registry.metrics()).containsEntry("activeListeners", 0);
        }
    }

    private static Map<String, Object> listen(String id, String country) {
        return Map.of("listenerId", id, "applicationId", "app", "country", country, "listenSeconds", 300L,
                "setupDeadline", System.currentTimeMillis() + 300_000,
                "templates", List.of(Map.of("id", "any", "priority", 1L, "kind", "ANY")));
    }

    @Test void unifiedInputsBindMessagesAndSmsToTheSelectedReplicaWithoutRebindingReporters() throws Exception {
        try (var fixture = new Fixture(1); var stops = new WorkerSimulatorScheduledStops(fixture.workers);
             var server = WorkerSimulatorControlServer.open(0, fixture.workers, stops); var http = HttpClient.newHttpClient()) {
            server.start();
            var base = server.baseUri();
            var descriptions = Jsons.parseArray(get(http, base, workerPath(FIRST) + ":inputs").body());
            assertThat(descriptions.stream().map(item -> (String) ((Map<?, ?>) item).get("eventName")))
                    .containsExactly("properties.update", "properties.replace", "sms.receive", "message.deliver", "message.read", "message.reply");
            var owner = fixture.workers.messageScenario();
            var sender = fixture.replicas.get(FIRST).sender;
            var reported = new ArrayList<Integer>();
            owner.send(sender, message("actual"), (tag, time, payload) -> { reported.add(tag); return true; });
            assertThat(input(http, base, SECOND, "message.deliver", Map.of("messageId", "actual")).statusCode()).isEqualTo(404);
            assertThat(input(http, base, FIRST, "message.read", Map.of("messageId", "actual")).statusCode()).isEqualTo(409);
            assertThat(reported).isEmpty();
            assertThat(Jsons.parseObject(get(http, base, workerPath(SECOND) + ":messages?offset=0&limit=1").body()))
                    .containsEntry("total", 0L).containsEntry("items", List.of());
            assertThat(Jsons.parseObject(get(http, base, workerPath(FIRST) + ":messages?offset=0&limit=1").body()))
                    .containsEntry("total", 1L);
            for (String query : List.of("?offset=-1", "?limit=1001", "?limit=1&limit=2", "?workerId=other"))
                assertThat(get(http, base, workerPath(FIRST) + ":messages" + query).statusCode()).isEqualTo(400);
            assertThat(input(http, base, FIRST, "message.deliver", Map.of("messageId", "actual", "tag", 9)).statusCode()).isEqualTo(400);
            assertThat(input(http, base, FIRST, "message.deliver", Map.of("messageId", "actual")).statusCode()).isEqualTo(200);
            assertThat(input(http, base, FIRST, "message.read", Map.of("messageId", "actual")).statusCode()).isEqualTo(200);
            var reply = Jsons.parseObject(input(http, base, FIRST, "message.reply", Map.of("messageId", "actual", "text", "reply")).body());
            assertThat(reply).containsEntry("sendAccepted", true);
            assertThat(reply.get("requestId")).isInstanceOf(String.class);
            var duplicate = Jsons.parseObject(input(http, base, FIRST, "message.reply",
                    Map.of("messageId", "actual", "text", "reply", "requestId", reply.get("requestId"))).body());
            assertThat(duplicate).containsEntry("unchanged", true);
            assertThat(reported).containsExactly(7, 8, 9);
            var registry = fixture.workers.smsScenario().registry;
            registry.listen(fixture.sims.get(FIRST), listen("sms", "CN"), (tag, time, payload) -> true);
            assertThat(input(http, base, FIRST, "sms.receive", Map.of("text", "hello", "smsId", "once", "phone", "wrong")).statusCode()).isEqualTo(400);
            assertThat(registry.metrics()).containsEntry("smsEvents", 0);
            var sms = Jsons.parseObject(input(http, base, FIRST, "sms.receive", Map.of("text", "hello")).body());
            assertThat(sms).containsEntry("status", "MATCHED").containsKey("smsId");
            assertThat(Jsons.parseObject(input(http, base, FIRST, "sms.receive",
                    Map.of("text", "hello", "smsId", sms.get("smsId"))).body())).containsEntry("status", "DUPLICATE");
            assertThat(input(http, base, FIRST, "properties.replace", Map.of()).statusCode()).isEqualTo(400);
            fixture.workers.stopWorker("demo-sim", FIRST); fixture.workers.startWorker("demo-sim", FIRST);
            assertThat(Jsons.parseObject(input(http, base, FIRST, "message.reply", Map.of("messageId", "actual", "text", "late")).body()))
                    .containsEntry("persisted", true).containsEntry("sendAccepted", false);
            assertThat(reported).containsExactly(7, 8, 9);
            owner.send(sender, message("direct"), com.xa.mass.worker.execution.WorkerOutcomeReporter.UNAVAILABLE);
            assertThat(Jsons.parseObject(input(http, base, FIRST, "message.deliver", Map.of("messageId", "direct")).body()))
                    .containsEntry("persisted", true).containsEntry("sendAccepted", false);
            for (String path : List.of("/lab/v1/sms/sms", "/lab/v1/messages/actual:deliver", "/lab/v1/messages/actual:read", "/lab/v1/messages/actual:reply"))
                assertThat(post(http, base, path).statusCode()).isEqualTo(404);
        }
    }

    @Test void invalidDevicePayloadsCannotConsumeBusinessIdentities() throws Exception {
        try (var fixture = new Fixture(1); var stops = new WorkerSimulatorScheduledStops(fixture.workers);
             var server = WorkerSimulatorControlServer.open(0, fixture.workers, stops); var http = HttpClient.newHttpClient()) {
            server.start();
            for (Map<String, Object> payload : List.<Map<String, Object>>of(Map.of(), Map.of("text", 1),
                    Map.of("text", "x".repeat(1025)), Map.of("text", "x", "smsId", ""),
                    Map.of("text", "x", "smsId", "x".repeat(129)), Map.of("text", "x", "forward", "forged"))) {
                assertThat(input(http, server.baseUri(), FIRST, "sms.receive", payload).statusCode()).isEqualTo(400);
            }
            for (Map<String, Object> payload : List.<Map<String, Object>>of(Map.of("messageId", List.of("x")),
                    Map.of("messageId", "x", "text", "x", "requestId", ""),
                    Map.of("messageId", "x", "text", "x".repeat(4097)), Map.of("messageId", "x", "text", "x", "tag", 9))) {
                assertThat(input(http, server.baseUri(), FIRST, "message.reply", payload).statusCode()).isEqualTo(400);
            }
            assertThat(fixture.workers.smsScenario().registry.metrics()).containsEntry("smsEvents", 0);
            assertThat(fixture.workers.messageScenario().metrics()).containsEntry("messages", 0).containsEntry("published", 0L);
            // Retain the SMS Owner's existing phone bound, not the shorter operation-ID bound.
            String phone = "1".repeat(200);
            fixture.workers.simulateInput("demo-sim", FIRST, "properties.update", Map.of("phone", phone));
            assertThat(input(http, server.baseUri(), FIRST, "sms.receive", Map.of("phone", phone, "text", "x"))
                    .statusCode()).isEqualTo(200);
            assertThat(input(http, server.baseUri(), FIRST, "sms.receive", Map.of("phone", "1".repeat(257), "text", "x"))
                    .statusCode()).isEqualTo(400);
        }
    }

    private static Map<String, Object> message(String id) {
        return Map.of("campaignId", "campaign", "messageId", id, "country", "CN", "recipientId", "recipient", "body", "body");
    }

    private static String workerPath(String key) { return "/lab/v1/workers/demo-sim/" + key; }

    private static HttpResponse<String> input(HttpClient http, URI base, String key, String event, Map<String, Object> payload) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(workerPath(key) + ":inputs")).timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(
                        Map.of("eventName", event, "payload", payload)))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient http, URI base, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(HttpClient http, URI base, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(3))
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
    }
}
