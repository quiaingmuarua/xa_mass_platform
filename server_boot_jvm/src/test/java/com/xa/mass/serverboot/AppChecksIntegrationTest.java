package com.xa.mass.serverboot;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.javase.JavaWorkerManager;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workersimulator.appchecks.AppRegistrationCheck;
import com.xa.mass.server.assembly.pacer.WorkerObservationWitness;
import com.xa.mass.workermatching.WorkerProperties;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.kernel.assignment.WorkerQuery;
import io.lettuce.core.RedisClient;
import io.lettuce.core.event.command.*;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.config.BeanPostProcessor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP/Redis/Worker proof. The attempt witness exists only in this test assembly. */
@Tag("scenario-composition")
class AppChecksIntegrationTest {
    @Test @Timeout(240)
    void observedWindowRejectsActualStockAndRecoversThroughNormalRecycling() throws Exception {
        try (var world = new World(1, 1); var worker = world.workers("app-a-sim")) {
            worker.start();
            List<String> ids = world.readyFacts(worker, "app-a-sim");
            // Leave room to witness a real rejected take in the original window, not a timing-only absence.
            while (60_000 - System.currentTimeMillis() % 60_000 < 20_000) Thread.sleep(100);
            String first = world.create("window-first", "app-a", 1, 1000, 1000, 0);
            world.settled(first, 1);
            world.assertAllocationProjection("app-a-sim", ids);
            var before = world.context.getBean(WorkerProperties.class).loadWorkerFacts("app-a-sim", ids)
                    .get(ids.getFirst()).platformProperties();
            long firstWindow = ((Number) before.get("lastAssignedAt")).longValue() / 60_000;
            assertThat(((Number) before.get("windowAssignmentCount")).longValue()).isEqualTo(1);
            String second = world.create("window-second", "app-a", 1, 1000, 1000, 0);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (world.selections.rejectedAt.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20);
            long rejectedAt = world.selections.rejectedAt.get();
            assertThat(rejectedAt).as("a nonempty offered batch was rejected during Pool refill; reads=%s takes=%s empty=%s",
                    world.selections.snapshots.get(), world.selections.takes.get(), world.selections.empty.get()).isPositive();
            assertThat(rejectedAt / 60_000).isEqualTo(firstWindow);
            assertThat(world.observations.snapshot()).hasSize(1);
            assertThat(task(world.get("/api/v1/app-checks/tasks/" + second))).containsEntry("activeCount", 1L);
            // No patch, direct acquisition, synthetic supply, Score write or retry mutation helps this request.
            var result = world.settled(second, 1, 180);
            assertThat(task(result)).containsEntry("succeededCount", 1L);
            world.assertAllocationProjection("app-a-sim", ids);
            assertThat(world.observations.snapshot()).hasSize(2);
            assertThat(world.observations.snapshot().getLast().observedAtMillis() / 60_000).isGreaterThan(firstWindow);
            assertThat(rows(result).getFirst()).containsEntry("workerId", ids.getFirst());
            assertThat(world.selections.takeSnapshots.get()).isZero();
            assertThat(world.selections.refillSnapshots.get()).isPositive();
        }
    }

    /** Observes the production read and take; does not change its candidates, properties or decisions. */
    static final class WindowSelectionWitness implements BeanPostProcessor {
        final AtomicLong snapshots = new AtomicLong();
        final AtomicLong rejectedAt = new AtomicLong();
        final AtomicLong takes = new AtomicLong();
        final AtomicLong empty = new AtomicLong();
        final AtomicLong takeSnapshots = new AtomicLong(), refillSnapshots = new AtomicLong();
        final ThreadLocal<String> phase = new ThreadLocal<>();
        @Override public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof RedisClient client) client.addListener(new CommandListener() {
                @Override public void commandStarted(CommandStartedEvent event) {
                    if (event.getCommand().getType().toString().equals("EVAL_RO")
                            && event.getCommand().getArgs().toCommandString().contains(":matching:worker:platform-properties:app-a-sim")) {
                        snapshots.incrementAndGet();
                        if ("take".equals(phase.get())) takeSnapshots.incrementAndGet();
                        if ("refill".equals(phase.get())) refillSnapshots.incrementAndGet();
                    }
                }
            });
            if (!(bean instanceof WorkerMatchingCatalog catalog)) return bean;
            var witnessed = spy(catalog);
            doAnswer(call -> {
                phase.set("take");
                try {
                    @SuppressWarnings("unchecked") var result = (Map<String, WorkerCandidate>) call.callRealMethod();
                    Map<String, WorkerQuery> inputs = call.getArgument(1);
                    if (!inputs.isEmpty()) { takes.incrementAndGet(); if (result.isEmpty()) empty.incrementAndGet(); }
                    return result;
                } finally { phase.remove(); }
            }).when(witnessed).take(anyString(), anyMap());
            doAnswer(call -> {
                phase.set("refill");
                try {
                    long before = refillSnapshots.get();
                    int accepted = (int) call.callRealMethod();
                    Map<String, Long> offered = call.getArgument(2);
                    if ("app-a-sim".equals(call.getArgument(0)) && !offered.isEmpty()
                            && accepted == 0 && refillSnapshots.get() > before)
                        rejectedAt.compareAndSet(0, System.currentTimeMillis());
                    return accepted;
                } finally { phase.remove(); }
            }).when(witnessed).refill(anyString(), anyList(), anyMap());
            return witnessed;
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1000,1000,0,false", "0,0,0,true", "1000,1000,6000,false"})
    @Timeout(90)
    void allocationProjectionPrecedesReportAndSurvivesSuccessFailureAndLateResults(
            int registered, int unregistered, int delay, boolean failure) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var world = new World(); var workers = world.workers("app-a-sim", entered, release)) {
            workers.start();
            // Establish the independent first Facts publication before exercising this lossy projection.
            List<String> ids = world.readyFacts(workers, "app-a-sim");
            String task = world.create("allocation-observation", "app-a", 1, registered, unregistered, delay);
            try {
                assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(rows(world.get("/api/v1/app-checks/tasks/" + task)))
                        .noneMatch(row -> "succeeded".equals(row.get("resultStatus")) || "failed".equals(row.get("resultStatus")));
                world.assertAllocationProjection("app-a-sim", ids);
            } finally { release.countDown(); }
            var result = world.settled(task, 1);
            assertThat(task(result)).containsEntry(failure ? "failedCount" : "succeededCount", 1L);
            if (failure) assertThat(world.attempts).anyMatch(Attempt::failed);
            else assertThat(rows(result)).allSatisfy(row -> assertThat(row)
                    .containsEntry("registered", true).containsEntry("simulatedDelayMillis", (long) delay));
            world.assertAllocationProjection("app-a-sim", ids);
            var saved = world.context.getBean(WorkerProperties.class).loadWorkerFacts("app-a-sim", ids);
            world.restart();
            var restored = world.context.getBean(WorkerProperties.class).loadWorkerFacts("app-a-sim", ids);
            ids.forEach(id -> assertThat(restored.get(id).platformProperties()).isEqualTo(saved.get(id).platformProperties()));
        } finally { release.countDown(); }
    }

    @Test @Timeout(150)
    void realHandlersIsolateAppsThrowFailuresAndRetainResultsAcrossServerRestart() throws Exception {
        try (var world = new World(); var a = world.workers("app-a-sim"); var b = world.workers("app-b-sim")) {
            a.start(); b.start();
            world.readyFacts(a, "app-a-sim");
            world.readyFacts(b, "app-b-sim");
            String registered = world.create("registered", "app-a", 101, 1000, 1000, 0);
            String unregistered = world.create("unregistered", "app-a", 4, 0, 1000, 0);
            String failed = world.create("failed", "app-b", 2, 0, 0, 0);
            String mixed = world.create("mixed", "app-b", 12, 500, 900, 10);
            var positive = world.settled(registered, 101);
            var negative = world.settled(unregistered, 4);
            var failures = world.settled(failed, 2);
            var mix = world.settled(mixed, 12);
            assertThat(task(positive)).containsEntry("succeededCount", 101L).containsEntry("failedCount", 0L);
            assertThat(rows(positive)).hasSizeLessThanOrEqualTo(100).isNotEmpty();
            assertThat(positive).containsEntry("resultsTruncated", true);
            assertThat(rows(positive)).allSatisfy(row -> assertThat(row).containsEntry("registered", true)
                    .containsEntry("workerGroupId", "app-a-sim"));
            assertThat(task(negative)).containsEntry("succeededCount", 4L).containsEntry("failedCount", 0L);
            assertThat(rows(negative)).allSatisfy(row -> assertThat(row).containsEntry("registered", false)
                    .containsEntry("resultStatus", "succeeded"));
            assertThat(task(failures)).containsEntry("failedCount", 2L).containsEntry("succeededCount", 0L);
            assertThat(rows(failures)).allSatisfy(row -> {
                assertThat(row).containsEntry("resultStatus", "failed").doesNotContainKey("registered");
                assertThat(world.attempts).anyMatch(attempt -> attempt.number().equals(row.get("number"))
                        && attempt.group().equals("app-b-sim") && attempt.failed());
            });
            for (var row : rows(mix)) {
                if (!"succeeded".equals(row.get("resultStatus"))) continue;
                assertThat(row).doesNotContainKey("contentError").containsEntry("workerGroupId", "app-b-sim");
                long bucket = hash("outcome", (String) row.get("workerId"), (String) task(mix).get("salt"), (String) row.get("number"));
                assertThat(bucket).isLessThan(900);
                assertThat(row.get("registered")).isEqualTo(bucket < 500);
            }
            // A delayed real Handler crosses the existing five-second claim. No single-execution assertion.
            String delayed = world.create("delayed", "app-a", 1, 1000, 1000, 6000);
            var late = world.settled(delayed, 1);
            assertThat(task(late)).containsEntry("succeededCount", 1L);
            assertThat(rows(late)).allSatisfy(row -> assertThat(row).containsEntry("simulatedDelayMillis", 6000L));
            assertThat(world.attempts).anyMatch(attempt -> attempt.number().equals(rows(late).getFirst().get("number"))
                    && !attempt.failed() && attempt.elapsedNanos() >= TimeUnit.SECONDS.toNanos(6));
            var csv = world.post("/api/v1/app-checks/tasks/" + registered + "/results:export?filter=registered", "", "application/json");
            assertThat(csv.statusCode()).isEqualTo(200);
            assertThat(csv.headers().firstValue("X-Export-Count")).contains("101");
            assertThat(csv.body().lines().count()).isEqualTo(102);
            var emptyCsv = world.post("/api/v1/app-checks/tasks/" + failed + "/results:export", "", "application/json");
            assertThat(emptyCsv.headers().firstValue("X-Export-Count")).contains("0");
            var original = task(negative);
            world.restart();
            var retained = world.get("/api/v1/app-checks/tasks/" + unregistered);
            assertThat(task(retained)).containsAllEntriesOf(original);
            assertThat(rows(retained)).containsExactlyInAnyOrderElementsOf(rows(negative));
            assertThat(world.get("/api/v1/app-checks/tasks?limit=100").get("tasks")).isInstanceOf(List.class);
        }
    }

    @Test @Timeout(180)
    void emptyCreationLargeImportIdempotencyAndManualClosureUseRealOwners() throws Exception {
        try (var world = new World()) {
            String path = "/api/v1/app-checks/tasks";
            var body = Map.of("requestId", "large-import", "appId", "app-a", "country", "CN", "simulation",
                    Map.of("ranges", Map.of("registered", List.of(0, 1000), "unregistered", List.of(1000, 1000), "failed", List.of(1000, 1000)), "delayMs", List.of(0, 0)));
            String json = Jsons.toJson(body);
            var created = world.post(path, json, "application/json");
            assertThat(created.statusCode()).isEqualTo(201);
            String id = (String) Jsons.parseObject(created.body()).get("taskId");
            String target = path + "/" + id;
            assertThat(task(world.get(target))).containsEntry("state", "pre_review").containsEntry("totalCount", 0L);
            assertThat(world.post(target + "/approve", "0", "application/json").statusCode()).isEqualTo(409);
            assertThat(world.post(target + "/numbers:import", "86123\nwrong", "text/plain").statusCode()).isEqualTo(400);
            assertThat(task(world.get(target))).containsEntry("totalCount", 0L);
            String numbers = IntStream.range(0, 100000).mapToObj(i -> "86138" + String.format("%08d", i)).collect(java.util.stream.Collectors.joining("\n"));
            var imported = world.post(target + "/numbers:import", numbers, "text/plain");
            assertThat(imported.statusCode()).as(imported.body()).isEqualTo(200);
            assertThat(Jsons.parseObject(imported.body())).containsEntry("addedCount", 100000L);
            var owner = world.context.getBean(com.xa.mass.kernel.task.TaskRuntime.class);
            // Discover only caller-known IDs through the App Checks import identity convention.
            String message = "number-" + numberIdentity("+8613800000000");
            var original = owner.loadTaskItems(id, List.of(message)).get(message);
            assertThat(original.expireAtMillis() - original.createdAtMillis()).isEqualTo(Duration.ofDays(365).toMillis());
            var duplicate = world.post(target + "/numbers:import", "+8613800000000\n8613800000000\n8613911111111", "text/plain");
            assertThat(Jsons.parseObject(duplicate.body())).containsEntry("addedCount", 1L).containsEntry("existingCount", 1L).containsEntry("duplicateCount", 1L);
            assertThat(owner.loadTaskItems(id, List.of(message)).get(message)).isEqualTo(original);
            world.restart();
            var repeated = world.post(path, json, "application/json");
            assertThat(repeated.statusCode()).isEqualTo(201);
            assertThat(Jsons.parseObject(repeated.body()).get("taskId")).isEqualTo(id);
            assertThat(task(world.get(target))).containsEntry("totalCount", 100001L).containsEntry("state", "pre_review");
            assertThat(world.post(target + "/approve", "100000", "application/json").statusCode()).isEqualTo(409);
            assertThat(world.post(target + "/approve", "100001", "application/json").statusCode()).isEqualTo(200);
            assertThat(world.post(target + "/close", "", "application/json").statusCode()).isEqualTo(200);
            assertThat(task(world.get(target))).containsEntry("activeCount", 100001L).containsEntry("state", "terminal");
            assertThat(world.post(target + "/approve", "100001", "application/json").statusCode()).isEqualTo(409);
        }
    }

    @Test @Timeout(45)
    void legacyAnySupplyRemainsReadableClosableAndExportableButCannotImportOrApprove() throws Exception {
        try (var world = new World()) {
            var legacy = new com.xa.mass.server.api.v1.contract.task.TaskCreateRequest("app-checks", "app-a-sim", 50, 3,
                    List.of(com.xa.mass.kernel.assignment.RefillTarget.of("any", new com.xa.mass.kernel.assignment.EligibilityQuery(Map.of()), 100)),
                    "legacy Any supply", Map.of("scenario", "app-checks", "appId", "app-a", "country", "CN", "inputVersion", "2", "salt", "retained"));
            String id = world.context.getBean(com.xa.mass.server.task.TaskCreationService.class).create(legacy).taskId();
            String route = "/api/v1/app-checks/tasks/" + id;
            assertThat(task(world.get(route))).containsEntry("state", "pre_review").containsEntry("inputVersion", "2")
                    .containsKey("inputUnavailableReason");
            assertThat(world.post(route + "/numbers:import", "86123", "text/plain").statusCode()).isEqualTo(409);
            assertThat(world.post(route + "/approve", "1", "application/json").statusCode()).isEqualTo(409);
            assertThat(world.post(route + "/close", "", "application/json").statusCode()).isEqualTo(200);
            assertThat(task(world.get(route))).containsEntry("state", "terminal");
            var exported = world.post(route + "/results:export", "", "application/json");
            assertThat(exported.statusCode()).isEqualTo(200);
            assertThat(exported.headers().firstValue("X-Export-Count")).contains("0");
            var descriptor = world.context.getBean(com.xa.mass.kernel.task.TaskResourceCatalog.class)
                    .loadTaskAllocationDescriptors(List.of(id)).get(id);
            assertThat(descriptor.refill().getFirst().poolName()).isEqualTo("any");
            assertThat(descriptor.metadata().get("salt")).isEqualTo("retained");
        }
    }

    static String numberIdentity(String number) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tuple = new DataOutputStream(bytes)) {
            for (String value : List.of("app-checks/v2/number", number)) { byte[] encoded = value.getBytes(StandardCharsets.UTF_8); tuple.writeInt(encoded.length); tuple.write(encoded); }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    @SuppressWarnings("unchecked") static Map<String, Object> task(Map<String, Object> detail) { return (Map<String, Object>) detail.get("task"); }
    @SuppressWarnings("unchecked") static List<Map<String, Object>> rows(Map<String, Object> detail) { return (List<Map<String, Object>>) detail.get("results"); }

    static long hash(String domain, String worker, String salt, String number) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var tuple = new DataOutputStream(bytes)) {
            for (String field : List.of("app-checks/v1/" + domain, worker, salt, number)) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8); tuple.writeInt(value.length); tuple.write(value);
            }
        }
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray());
        return new BigInteger(1, Arrays.copyOf(digest, 8)).mod(BigInteger.valueOf(1000)).longValueExact();
    }

    record Attempt(String group, String number, boolean failed, long elapsedNanos) {}

    static final class World implements AutoCloseable {
        final String scope = "test_app_checks_" + UUID.randomUUID().toString().replace("-", "");
        final String redisUrl = System.getenv().getOrDefault("XA_MASS_REDIS_URL", "redis://127.0.0.1:6379/15");
        final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final Queue<Attempt> attempts = new ConcurrentLinkedQueue<>();
        final WorkerObservationWitness observations = new WorkerObservationWitness();
        final WindowSelectionWitness selections = new WindowSelectionWitness();
        final List<String> replicas;
        final URI base;
        final SpringApplication application = new SpringApplication(ServerBootConfiguration.class);
        final String[] arguments;
        ConfigurableApplicationContext context;
        int sequence;
        World() throws Exception {
            this(1000, 2);
        }
        World(long maxAssignments, int workerCount) throws Exception {
            replicas = IntStream.range(0, workerCount).mapToObj(i -> "replica-" + i).toList();
            int port = port(), adapter = port(); base = URI.create("http://127.0.0.1:" + port);
            application.setRegisterShutdownHook(false);
            application.addInitializers(ctx -> ctx.getBeanFactory().addBeanPostProcessor(observations));
            application.addInitializers(ctx -> ctx.getBeanFactory().addBeanPostProcessor(selections));
            arguments = new String[]{"--spring.profiles.active=preview", "--server.port=" + port,
                    "--xa.mass.worker-pools.assignment-window.groups.app-a-sim.max-count=" + maxAssignments,
                    "--xa.mass.worker-pools.assignment-window.groups.app-b-sim.max-count=" + maxAssignments,
                    "--xa.mass.redis.url=" + redisUrl, "--xa.mass.redis.scope=" + scope,
                    "--xa.mass.worker-delivery.adapter.remote-base-url=" + base,
                    "--xa.mass.worker-delivery.adapter.instances.products-websocket.listen-port=" + adapter,
                    "--xa.mass.worker-endpoints.endpoints.products-websocket.public-uri=ws://127.0.0.1:" + adapter + "/api/v1/worker-delivery/websocket",
                    "--logging.level.root=ERROR"};
            context = application.run(arguments);
        }
        JavaWorkerManager workers(String group) {
            return workers(group, null, null);
        }
        JavaWorkerManager workers(String group, CountDownLatch entered, CountDownLatch release) {
            var reference = new AtomicReference<JavaWorkerManager>();
            var builder = JavaWorkerManager.builder(base, group, WorkerTransportType.WEBSOCKET);
            for (String replica : replicas) {
                var actual = AppRegistrationCheck.definition(group, () -> reference.get().snapshot(replica).workerId());
                var witnessed = WorkerEventDefinition.extension("app.registration.check", WorkerEventParameterResolvers.jsonMap(), input -> {
                    long start = System.nanoTime(); boolean failed = false;
                    try { return actual.handler().execute(input); }
                    catch (WorkerException error) { failed = error.errorCode() == WorkerErrorCode.EVENT_EXECUTION_FAILED; throw error; }
                    finally {
                        if (entered != null) {
                            entered.countDown();
                            try { if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Report gate was not released"); }
                            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                        }
                        if (attempts.size() >= 500) throw new AssertionError("Bounded attempt witness overflow");
                        attempts.add(new Attempt(group, (String) input.get("number"), failed, System.nanoTime() - start));
                    }
                });
                builder.replica(replica, () -> Map.of("simulated", "true"), List.of(witnessed));
            }
            var manager = builder.build(); reference.set(manager); return manager;
        }
        List<String> readyFacts(JavaWorkerManager manager, String group) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            do {
                var ids = replicas.stream().map(replica -> manager.snapshot(replica).workerId())
                        .filter(Objects::nonNull).toList();
                if (ids.size() == replicas.size() && context.getBean(WorkerProperties.class).loadWorkerFacts(group, ids).values().stream()
                        .filter(Objects::nonNull).count() == replicas.size()) return ids;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Worker Facts publication was not established");
        }
        void assertAllocationProjection(String group, List<String> ids) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do {
                var snapshot = observations.snapshot();
                var times = new LinkedHashMap<String, List<Long>>();
                snapshot.stream().filter(observation -> observation.workerGroupId().equals(group)
                        && observation.observationEventName().equals("worker.assigned")
                        && observation.messageEventName().equals("extension.worker.app.registration.check"))
                        .forEach(observation -> observation.workerIds().forEach(id -> times.computeIfAbsent(id, ignored -> new ArrayList<>())
                                .add(observation.observedAtMillis())));
                var facts = context.getBean(WorkerProperties.class).loadWorkerFacts(group, ids);
                boolean matches = !times.isEmpty();
                for (var worker : times.entrySet()) {
                    long last = worker.getValue().stream().mapToLong(Long::longValue).max().orElseThrow();
                    long count = worker.getValue().stream().filter(time -> time / 60_000 == last / 60_000).count();
                    var values = facts.get(worker.getKey()).platformProperties();
                    matches &= values.get("lastAssignedAt") instanceof Number at && at.longValue() == last
                            && values.get("windowAssignmentCount") instanceof Number n && n.longValue() == count;
                }
                if (matches && observations.snapshot().equals(snapshot)) return;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Platform window did not match the bounded allocation source witness");
        }
        String create(String request, String app, int count, int registered, int unregistered, int delay) throws Exception {
            int offset = ++sequence * 1000;
            var numbers = IntStream.range(offset, offset + count).mapToObj(i -> "+8613800" + String.format("%06d", i)).toList();
            var body = Map.of("requestId", request, "appId", app, "country", "CN",
                    "simulation", Map.of("ranges", Map.of("registered", List.of(0, registered), "unregistered", List.of(registered, unregistered),
                            "failed", List.of(unregistered, 1000)), "delayMs", List.of(delay, delay)));
            var response = http.send(HttpRequest.newBuilder(base.resolve("/api/v1/app-checks/tasks"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Jsons.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            String id = (String) Jsons.parseObject(response.body()).get("taskId");
            assertThat(post("/api/v1/app-checks/tasks/" + id + "/numbers:import", String.join("\n", numbers), "text/plain").statusCode()).isEqualTo(200);
            assertThat(post("/api/v1/app-checks/tasks/" + id + "/approve", Integer.toString(count), "application/json").statusCode()).isEqualTo(200);
            return id;
        }
        HttpResponse<String> post(String path, String body, String contentType) throws Exception {
            return http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(90))
                    .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
        Map<String, Object> settled(String id, int total) throws Exception {
            return settled(id, total, 45);
        }
        Map<String, Object> settled(String id, int total, long seconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            do {
                var detail = get("/api/v1/app-checks/tasks/" + id); var task = task(detail);
                if (Long.valueOf(total).equals(task.get("totalCount")) && Long.valueOf(0).equals(task.get("activeCount"))
                        && "terminal".equals(task.get("state"))
                        && (long) task.get("succeededCount") + (long) task.get("failedCount") == total
                        && !rows(detail).isEmpty()) return detail;
                Thread.sleep(100);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("Task did not close its finite execution observations");
        }
        Map<String, Object> get(String path) throws Exception {
            var response = http.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200); return Jsons.parseObject(response.body());
        }
        void restart() { context.close(); context = application.run(arguments); }
        public void close() {
            context.close(); http.close();
            var client = RedisClient.create(redisUrl);
            try (var connection = client.connect()) {
                ScanCursor cursor = ScanCursor.INITIAL;
                do { var page = connection.sync().scan(cursor, ScanArgs.Builder.matches("xa_mass:" + scope + ":*").limit(1000));
                    if (!page.getKeys().isEmpty()) connection.sync().unlink(page.getKeys().toArray(String[]::new)); cursor = page;
                } while (!cursor.isFinished());
            } finally { client.shutdown(Duration.ZERO, Duration.ofSeconds(3)); }
        }
        static int port() throws Exception { try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); } }
    }
}
