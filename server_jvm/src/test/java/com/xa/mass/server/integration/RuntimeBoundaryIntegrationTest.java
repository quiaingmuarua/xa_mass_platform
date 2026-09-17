package com.xa.mass.server.integration;

import static org.mockito.ArgumentMatchers.anyString;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerOutcomeReporter;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerManagementEventDefinitions;
import com.xa.mass.worker.javase.JavaWorker;
import com.xa.mass.workermatching.WorkerMatchingCatalog;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.pool.CandidatePool;

import com.xa.mass.workermatching.functions.PoolQueryFunctions;
import com.xa.mass.workermatching.storage.FactsIndexStore;
import com.xa.mass.server.testsupport.BucketPoolFixture;
import com.xa.mass.server.testsupport.IdentityHintPoolFixture;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import com.xa.mass.server.assembly.matching.MatchingProperties;
import com.xa.mass.server.assembly.redis.XaMassRedisProperties;
import com.xa.mass.server.worker.preparation.WorkerPreparationService;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import static com.xa.mass.kernel.score.redis.WorkerScoreRedisFixture.*;
import com.xa.mass.server.assembly.pacer.KernelPacerAssembly;
import com.xa.mass.server.task.call.TaskRpcResultProbe;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.assembly.runtime
        .ServerConfiguredRuntimeLifecycleHost;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerPointClient;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import com.xa.mass.kernel.assignment.WorkerMatching.HeldCandidate;
import com.xa.mass.kernel.assignment.RefillTarget;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles({"test", "integration-test"})
@SpringBootTest(classes = {com.xa.mass.server.testsupport.ServerTestConfiguration.class, RuntimeBoundaryIntegrationTest.MatchingTestAssembly.class}, properties="spring.main.allow-bean-definition-overriding=true", webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ContextConfiguration(
        initializers = RuntimeBoundaryIntegrationTest
                .DedicatedRedisInitializer.class
)
@Tag("runtime-boundary")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeBoundaryIntegrationTest {

    @TestConfiguration(proxyBeanMethods=false)
    static class MatchingTestAssembly {
        @Bean(destroyMethod="close") FactsIndexStore matchingFactsStore(
                RedisClient client, XaMassRedisProperties redis, MatchingProperties rules) {
            var indexes=new LinkedHashMap<>(MatchingComposition.indexes(rules.groups()));
            rules.groups().forEach((group,config)->{if(config.pools().contains(BucketPoolFixture.ID)) {
                var all=new ArrayList<>(indexes.getOrDefault(group,List.of()));
                all.addAll(BucketPoolFixture.indexes()); indexes.put(group,List.copyOf(all));
            }});
            return new FactsIndexStore(client,redis.keyspace(),indexes);
        }
        @Bean MatchingComposition matchingTestComposition(FactsIndexStore storage,MatchingProperties rules) {
            return new MatchingComposition(storage,rules.groups(),System::currentTimeMillis);
        }
        @Bean IdentityHintPoolFixture identityHintRule(MatchingComposition composition) {
            return new IdentityHintPoolFixture(System::currentTimeMillis,
                    new CandidatePool(System::currentTimeMillis,composition.budget()));
        }
        @Bean(destroyMethod="close") RedisWorkerMatchingCatalog workerMatchingCatalog(
                FactsIndexStore storage,MatchingComposition composition,MatchingProperties rules,
                IdentityHintPoolFixture identityHintRule) {
            var stock=new CandidatePool(System::currentTimeMillis,composition.budget());
            var bucket=new BucketPoolFixture(System::currentTimeMillis,storage,stock,false);
            var pools=new LinkedHashMap<>(composition.pools());
            pools.put(BucketPoolFixture.ID,stock); pools.put(IdentityHintPoolFixture.ID,identityHintRule.stock());
            var handlers=new LinkedHashMap<>(composition.policies());
            handlers.put(BucketPoolFixture.ID,bucket); handlers.put(IdentityHintPoolFixture.ID,identityHintRule);
            var functions=new LinkedHashMap<>(composition.functions());
            functions.put(BucketPoolFixture.ID,bucket.functions()); functions.put(IdentityHintPoolFixture.ID,identityHintRule.queryFunctions());
            var messaging = PoolQueryFunctions.messaging(composition.pools().get("messaging"));
            functions.put("proof.messaging.country", new QueryFunctions((g,input)-> {
                if (!(input instanceof String country)) throw new IllegalArgumentException("country string required");
                messaging.normalizeInput().apply(g,Map.of("country",List.of(country))); return country;
            }, (g,inputs)-> {
                var local=new LinkedHashMap<String,Object>(); inputs.forEach((id,input)->local.put(id,Map.of("country",List.of(input))));
                return messaging.execute().apply(g,local);
            }));
            functions.put("proof.messaging.phones", new QueryFunctions((g,input)-> {
                if (!(input instanceof List<?> phones) || phones.size()!=1 || !(phones.getFirst() instanceof String phone))
                    throw new IllegalArgumentException("one phone required");
                messaging.normalizeInput().apply(g,Map.of("phone",phone)); return List.of(phone);
            }, (g,inputs)-> {
                var local=new LinkedHashMap<String,Object>(); inputs.forEach((id,input)->local.put(id,Map.of("phone",((List<?>)input).getFirst())));
                return messaging.execute().apply(g,local);
            }));
            var catalog=new RedisWorkerMatchingCatalog(storage,composition.budget(),pools,
                    System::currentTimeMillis,handlers,functions,rules.groups());
            try { storage.rebuildIndexes(); return catalog; }
            catch(RuntimeException failure) { catalog.close(); throw failure; }
        }

    }

    private static final int SERVER_PORT = availablePort();
    private static final int[] ACTIVE_ADAPTER_PORTS =
            availablePorts(2);
    private static final String WEBSOCKET_ENDPOINT_MANAGER_ID =
            "java-websocket-integration";
    private static final String SOCKET_ENDPOINT_MANAGER_ID =
            "java-socket-integration";
    private static final String TEST_CAPABILITY =
            "test.integration.observe";
    private static final String TEST_EVENT_CODE =
            "extension.worker." + TEST_CAPABILITY;
    private static final String DIRECT_CAPABILITY =
            "test.integration.direct-snapshot";
    private static final String DIRECT_EVENT_CODE =
            "extension.worker." + DIRECT_CAPABILITY;
    // Covers two complete 5-second lease recovery windows under CI load.
    private static final Duration RESULT_CONVERGENCE_TIMEOUT =
            Duration.ofSeconds(15);
    // Retains the existing boundary bound for normal Producer rounds and the
    // Adapter/Result evidence handoff; an empty Worker read adds no cooldown.
    private static final Duration SERVICEABILITY_CONVERGENCE_TIMEOUT =
            Duration.ofSeconds(15);
    private static final String SERVICEABILITY_WORKER_GROUP_ID =
            "serviceability-runtime-boundary";
    private static final String TEST_RESULT = "{\"observed\":\"input\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RedisTestScope REDIS_TEST_SCOPE =
            RedisTestScope.create("runtime_boundary");
    private static final RedisTestScope OUTSIDE_REDIS_TEST_SCOPE =
            RedisTestScope.create("runtime_boundary_sentinel");
    private static final RedisKeyspace REDIS_KEYSPACE =
            REDIS_TEST_SCOPE.keyspace();
    private static final String OUTSIDE_SENTINEL_KEY =
            OUTSIDE_REDIS_TEST_SCOPE.keyspace().base() + ":worker:groups";

    public static final class DedicatedRedisInitializer implements
            ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            RedisClient client = RedisClient.create(REDIS_URL);
            try (var connection = client.connect(StringCodec.UTF8)) {
                connection.sync().hset(
                        OUTSIDE_SENTINEL_KEY,
                        "runtime-boundary",
                        "untouched"
                );
            } finally {
                client.shutdown();
            }
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WorkerScoreCore workerScores;

    @Autowired
    private IdentityHintPoolFixture identityHintRule;

    @Autowired
    private RedisClient redisWitnessClient;
    private io.lettuce.core.api.StatefulRedisConnection<String, String> redisWitness;

    @org.junit.jupiter.api.BeforeEach
    void openScoreWitness() { redisWitness = redisWitnessClient.connect(); }

    @org.junit.jupiter.api.AfterEach
    void closeScoreWitness() { redisWitness.close(); }

    @MockitoSpyBean
    private com.xa.mass.kernel.delivery.TaskEvidenceRuntime taskEvidence;

    @Autowired
    private KernelPacerAssembly kernelPacerAssembly;

    @Autowired
    private TaskRpcResultProbe taskRpcResultProbe;

    @Autowired
    private ServerConfiguredRuntimeLifecycleHost workerAssemblyLifecycleHost;

    @MockitoSpyBean
    private WorkerMatchingCatalog matchingCatalog;
    @Autowired
    private com.xa.mass.kernel.task.TaskResourceCatalog taskCatalog;
    @MockitoSpyBean
    private WorkerPreparationService preparationService;

    private static String poolName(String function) { return switch(function) { case "worker.any" -> "any"; case "worker.country" -> "country"; case "worker.messaging.available" -> "messaging"; case "proof.worker.facts" -> "proof-facts"; default -> function; }; }

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add("xa.mass.redis.url", () -> REDIS_URL);
        for(String group:List.of("group-refill-b",SERVICEABILITY_WORKER_GROUP_ID)) {
            registry.add("xa.mass.worker-matching.groups["+group+"].pools[0]",()->"any");
            registry.add("xa.mass.worker-matching.groups["+group+"].functions[0]",()->"worker.any");
        }
        for(String group:List.of("country-index-websocket","country-index-socket")) {
            registry.add("xa.mass.task-rpc.refill-by-worker-group["+group+"][0]",
                    ()->"{\"poolName\":\"country\",\"target\":{},\"count\":100}");
        }
        registry.add("xa.mass.task-rpc.refill-by-worker-group[direct-query-boundary]", () -> "");
        registry.add("xa.mass.worker-matching.groups[country-index-websocket].functions[0]", () -> "worker.country");
        registry.add("xa.mass.worker-matching.groups[country-index-websocket].pools[0]", () -> "country");
        registry.add("xa.mass.worker-matching.groups[shared-eligibility-boundary].functions[0]", () -> BucketPoolFixture.ID);
        registry.add("xa.mass.worker-matching.groups[shared-eligibility-boundary].pools[0]", () -> BucketPoolFixture.ID);
        registry.add("xa.mass.worker-matching.groups[identity-hint-boundary].functions[0]", () -> IdentityHintPoolFixture.ID);
        registry.add("xa.mass.worker-matching.groups[identity-hint-boundary].pools[0]", () -> IdentityHintPoolFixture.ID);
        registry.add("xa.mass.worker-matching.groups[direct-query-boundary].functions[0]", () -> "worker.phone");
        registry.add("xa.mass.worker-matching.groups[group-refill-a].functions[0]", () -> "worker.country");
        registry.add("xa.mass.worker-matching.groups[group-refill-a].pools[0]", () -> "country");
        registry.add("xa.mass.worker-matching.groups[group-refill-a].functions[1]", () -> "worker.messaging.available");
        registry.add("xa.mass.worker-matching.groups[group-refill-a].pools[1]", () -> "messaging");
        registry.add("xa.mass.worker-matching.groups[country-index-socket].functions[0]", () -> "worker.country");
        registry.add("xa.mass.worker-matching.groups[country-index-socket].pools[0]", () -> "country");
        registry.add("xa.mass.worker-matching.groups[property-tools-boundary].functions[0]", () -> "proof.worker.facts");
        registry.add("xa.mass.worker-matching.groups[property-tools-boundary].pools[0]", () -> "proof-facts");
        registry.add("xa.mass.worker-matching.groups[shared-pool-functions].pools[0]", () -> "messaging");
        registry.add("xa.mass.worker-matching.groups[shared-pool-functions].functions[0]", () -> "proof.messaging.country");
        registry.add("xa.mass.worker-matching.groups[shared-pool-functions].functions[1]", () -> "proof.messaging.phones");
        registry.add("xa.mass.task-rpc.refill-by-worker-group[shared-pool-functions]", () -> "");
        registry.add("xa.mass.task-item-outcomes.names[7]", () -> "delivered");
        registry.add("xa.mass.task-item-outcomes.names[8]", () -> "read");
        registry.add("xa.mass.task-item-outcomes.names[9]", () -> "replied");
        registry.add("xa.mass.diagnostics.enabled", () -> "true");
        registry.add(
                "xa.mass.redis.scope",
                REDIS_TEST_SCOPE::scope
        );
        registry.add(
                "server.port",
                () -> Integer.toString(SERVER_PORT)
        );
        registry.add(
                "xa.mass.worker-delivery.adapter.remote-base-url",
                () -> "http://127.0.0.1:" + SERVER_PORT
        );
        registry.add(
                "xa.mass.worker-delivery.adapter.remote-request-timeout",
                () -> "2s"
        );
        registry.add(
                "xa.mass.worker-endpoints.endpoints.system-polling."
                        + "transport-type",
                () -> "POLLING"
        );
        registry.add(
                "xa.mass.worker-endpoints.endpoints.system-polling.public-uri",
                () -> "http://127.0.0.1:" + SERVER_PORT
        );
        addWebSocketAdapter(
                registry,
                WEBSOCKET_ENDPOINT_MANAGER_ID,
                ACTIVE_ADAPTER_PORTS[0]
        );
        addSocketAdapter(
                registry,
                SOCKET_ENDPOINT_MANAGER_ID,
                ACTIVE_ADAPTER_PORTS[1]
        );
    }

    @Test
    void singleAndBatchPrepareKeepIdentityAndScoreWithoutCreatingMatchingFacts() throws Exception {
        for (String kind : List.of("CLIENT_KEY", "SCENARIO_LAB")) {
            String groupId = "prepare-only-" + UUID.randomUUID();
            assertThat(send("POST", "/api/v1/worker-groups/" + groupId + ":register",
                    "{\"eventCodes\":[]}").statusCode()).isEqualTo(200);
            List<Map<String, Object>> requests = new ArrayList<>();
            for (int i = 1; i <= 2; i++) {
                Map<String, Object> coordinates = kind.equals("CLIENT_KEY")
                        ? Map.of("clientWorkerKey", "installation-" + i)
                        : Map.of("labInventoryKey", "workers.jsonl", "labInventoryLine", Integer.toString(i));
                Map<String, Object> properties = new LinkedHashMap<>(coordinates);
                properties.put("ignored", Map.of("nested", List.of(1, 2)));
                requests.add(Map.of("workerKind", kind, "transportType", "POLLING", "workerProperties", properties));
            }
            String route = "/api/v1/worker-groups/" + groupId + "/workers:prepare";
            HttpResponse<String> single = send("POST", route, JSON.writeValueAsString(requests.getFirst()));
            assertThat(single.statusCode()).isEqualTo(200);
            String firstId = JSON.readTree(single.body()).get("workerId").asText();
            HttpResponse<String> batch = send("POST", route + "-batch", JSON.writeValueAsString(requests));
            assertThat(batch.statusCode()).isEqualTo(200);
            var rows = JSON.readTree(batch.body());
            assertThat(rows.size()).isEqualTo(2);
            assertThat(rows.get(0).get("workerId").asText()).isEqualTo(firstId);
            String secondId = rows.get(1).get("workerId").asText();
            assertThat(secondId).isNotEqualTo(firstId);
            List<String> ids = List.of(firstId, secondId);
            assertThat(matchingCatalog.loadWorkerFacts(groupId, ids).values()).containsOnlyNulls();
            assertThat(send("PATCH", "/api/v1/worker-groups/" + groupId + "/workers/" + firstId
                    + "/platform-properties", "{\"pool\":\"a\"}").statusCode()).isEqualTo(400);
            for (boolean pause : List.of(false, true)) {
                if (pause) ids.forEach(id -> workerScores.pauseScheduling(groupId, id));
                else {
                    var times = new LinkedHashMap<String, Long>();
                    ids.forEach(id -> times.put(id, System.currentTimeMillis() + 60_000));
                    workerScores.rewriteCurrentPolarityWithinTimeFence(groupId, times,
                            WorkerScorePolarity.RECOVERY_RECHECK, true);
                }
                var before = readWorkerFences(groupId, ids);
                assertThat(send("POST", route + "-batch", JSON.writeValueAsString(requests)).body())
                        .isEqualTo(batch.body());
                assertThat(readWorkerFences(groupId, ids)).isEqualTo(before);
            }
            var preview = JSON.readTree(send("POST", "/api/v1/runtime-view/worker-groups/" + groupId
                    + "/workers:preview", "2").body());
            assertThat(preview.get("returnedCount").asInt()).isEqualTo(2);
            assertThat(preview.get("unreadableCount").asInt()).isZero();
            for (var row : preview.get("workers")) {
                assertThat(row.get("workerProperties").isEmpty()).isTrue();
                assertThat(row.get("platformProperties").isEmpty()).isTrue();
            }
            assertThat(matchingCatalog.loadWorkerFacts(groupId, ids).values()).containsOnlyNulls();
        }
    }

    @Test
    void runtimePropertiesReachMatchingWithoutAnotherPrepareOnBothTextProtocols() throws Exception {
        for (WorkerTransportType type : List.of(WorkerTransportType.WEBSOCKET, WorkerTransportType.SOCKET)) {
            String groupId = "live-properties-" + UUID.randomUUID();
            String adapterId = type == WorkerTransportType.WEBSOCKET
                    ? WEBSOCKET_ENDPOINT_MANAGER_ID : SOCKET_ENDPOINT_MANAGER_ID;
            assertThat(send("POST", "/api/v1/worker-groups/" + groupId + ":register",
                    "{\"eventCodes\":[]}").statusCode()).isEqualTo(200);
            AtomicReference<Map<String, String>> host = new AtomicReference<>(Map.of("network.type", "wifi", "ssid", "lab"));
            CountDownLatch firstObservation = new CountDownLatch(1);
            AtomicInteger firstSubmissions = new AtomicInteger();
            doAnswer(invocation -> {
                Map<String, ?> snapshots = invocation.getArgument(1);
                if (snapshots.containsValue(host.get())) {
                    firstSubmissions.incrementAndGet();
                    firstObservation.countDown();
                    throw new IllegalStateException("Matching facts unavailable");
                }
                return invocation.callRealMethod();
            }).when(matchingCatalog).upsertWorkerFactsBatch(eq(groupId), anyMap());
            try (JavaWorker worker = JavaWorker.create(URI.create("http://127.0.0.1:" + port),
                    groupId, "live-host", type, host::get)) {
                worker.start();
                awaitCondition(() -> worker.snapshot().workerId() != null);
                String workerId = worker.snapshot().workerId();
                assertThat(firstObservation.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(matchingCatalog.loadWorkerFacts(groupId, List.of(workerId))).containsEntry(workerId, null);
                awaitRuntimeProperties(groupId, workerId, adapterId, Map.of());
                Thread.sleep(150); // SYSTEM failure has no automatic replay, including the first baseline.
                assertThat(firstSubmissions).hasValue(1);
                verify(preparationService, times(1)).prepareAll(eq(groupId), any(), any(), anyList());
                doCallRealMethod().when(matchingCatalog).upsertWorkerFactsBatch(eq(groupId), anyMap());
                assertThat(worker.reportProperties()).isTrue();
                awaitRuntimeProperties(groupId, workerId, adapterId, host.get());
                URI endpoint = worker.snapshot().endpointUri();
                long holdUntil = System.currentTimeMillis() + 60_000;
                long observed = readWorkerFences(groupId, List.of(workerId)).get(workerId);
                workerScores.acquireObservedHotScoreLeases(groupId, Map.of(workerId, observed), holdUntil);
                long held = readWorkerFences(groupId, List.of(workerId)).get(workerId);

                host.set(Map.of("network.type", "cellular", "ssid", "lab"));
                assertThat(worker.reportProperties(Map.of("network.type", "cellular"))).isTrue();
                awaitRuntimeProperties(groupId, workerId, adapterId, host.get());
                assertThat(mark(readWorkerFences(groupId,List.of(workerId)).get(workerId))).isEqualTo(1);

                // Re-Prepare cannot replace the observed baseline with stale startup input.
                var scoreBeforePrepare = readWorkerFences(groupId, List.of(workerId));
                PreparedCoordinate repeated = prepareWorker(groupId, "live-host",
                        type == WorkerTransportType.WEBSOCKET ? TransportProfile.WEBSOCKET : TransportProfile.SOCKET,
                        Map.of("network.type", "stale-startup"));
                assertThat(repeated.workerId()).isEqualTo(workerId);
                assertThat(repeated.endpointUri()).isEqualTo(endpoint);
                assertThat(matchingCatalog.loadWorkerFacts(groupId, List.of(workerId)).get(workerId)
                        .workerProperties()).isEqualTo(host.get());
                assertThat(readWorkerFences(groupId, List.of(workerId))).isEqualTo(scoreBeforePrepare);

                host.set(Map.of());
                assertThat(worker.reportProperties()).isTrue();
                awaitRuntimeProperties(groupId, workerId, adapterId, Map.of());

                CountDownLatch failedSubmission = new CountDownLatch(1);
                AtomicInteger submissions = new AtomicInteger();
                doAnswer(invocation -> {
                    Map<String, ?> snapshots = invocation.getArgument(1);
                    if (snapshots.containsKey(workerId) && submissions.incrementAndGet() == 1) {
                        failedSubmission.countDown();
                        throw new IllegalStateException("Matching facts unavailable");
                    }
                    return invocation.callRealMethod();
                }).when(matchingCatalog).upsertWorkerFactsBatch(eq(groupId), anyMap());
                host.set(Map.of("network.type", "recovered"));
                assertThat(worker.reportProperties(Map.of("network.type", "recovered"))).isTrue();
                assertThat(failedSubmission.await(5, TimeUnit.SECONDS)).isTrue();
                Thread.sleep(150); // Exceeds multiple configured 20ms Report backoffs; no automatic replay.
                assertThat(submissions).hasValue(1);
                assertThat(matchingCatalog.loadWorkerFacts(groupId, List.of(workerId)).get(workerId)
                        .workerProperties()).isEmpty();
                assertThat(worker.reportProperties()).isTrue(); // New Host input, not transport repair.
                awaitRuntimeProperties(groupId, workerId, adapterId, host.get());
                assertThat(submissions).hasValue(2);
                assertThat(worker.snapshot().workerId()).isEqualTo(workerId);
                assertThat(worker.snapshot().endpointUri()).isEqualTo(endpoint);
                verify(preparationService, times(2)).prepareAll(eq(groupId), any(), any(), anyList());
            } finally {
                doCallRealMethod().when(matchingCatalog).upsertWorkerFactsBatch(eq(groupId), anyMap());
            }
        }
    }

    @Test
    void countryIndexSelectsActualExecutorAfterLiveCountrySwapWithoutReprepare() throws Exception {
        for (WorkerTransportType type : List.of(WorkerTransportType.WEBSOCKET, WorkerTransportType.SOCKET)) {
            String group = type == WorkerTransportType.WEBSOCKET ? "country-index-websocket" : "country-index-socket";
            String adapter = type == WorkerTransportType.WEBSOCKET ? WEBSOCKET_ENDPOINT_MANAGER_ID : SOCKET_ENDPOINT_MANAGER_ID;
            var registration = send("POST", "/api/v1/worker-groups/" + group + ":register",
                    "{\"eventCodes\":[\"extension.worker.country.executor\"]}");
            assertThat(registration.statusCode()).isEqualTo(200);
            String taskId = JSON.readTree(registration.body()).get("taskId").asText();
            var firstProperties = new AtomicReference<>(Map.of("country", "CN", "host", "first"));
            var secondProperties = new AtomicReference<>(Map.of("country", "US", "host", "second"));
            var firstRef = new AtomicReference<JavaWorker>();
            var secondRef = new AtomicReference<JavaWorker>();
            var firstOutcome = new AtomicReference<WorkerOutcomeReporter>();
            var firstEvent = WorkerEventDefinition.extension("country.executor", WorkerEventParameterResolvers.jsonMap(),
                    (ignored, reporter) -> {
                        firstOutcome.set(reporter);
                        return Jsons.toJson(Map.of("executor", firstRef.get().snapshot().workerId(), "host", "first"));
                    });
            var secondEvent = WorkerEventDefinition.extension("country.executor", WorkerEventParameterResolvers.jsonMap(),
                    ignored -> Jsons.toJson(Map.of("executor", secondRef.get().snapshot().workerId(), "host", "second")));
            try (var first = JavaWorker.create(URI.create("http://127.0.0.1:" + port), group, "first", type,
                         firstProperties::get, List.of(firstEvent), WorkerConnectionOptions.of(Duration.ofSeconds(2), connectionPolicy()));
                 var second = JavaWorker.create(URI.create("http://127.0.0.1:" + port), group, "second", type,
                         secondProperties::get, List.of(secondEvent), WorkerConnectionOptions.of(Duration.ofSeconds(2), connectionPolicy()))) {
                firstRef.set(first); secondRef.set(second);
                first.start(); second.start();
                awaitCondition(() -> first.snapshot().workerId() != null && second.snapshot().workerId() != null);
                String firstId = first.snapshot().workerId(), secondId = second.snapshot().workerId();
                awaitRuntimeProperties(group, firstId, adapter, firstProperties.get());
                awaitRuntimeProperties(group, secondId, adapter, secondProperties.get());
                assertCountryExecutors(taskId, Map.of("CN", Map.of("executor", firstId, "host", "first"), "US", Map.of("executor", secondId, "host", "second")));
                String indexedFirst = createIndexedTask(group);
                String indexedSecond = createIndexedTask(group);
                String indexedItem = assertFiniteCountryExecutor(indexedFirst, "CN", firstId, "first");
                assertThat(firstOutcome.get().report(9, System.currentTimeMillis(), "reply after Task closure")).isTrue();
                awaitTrackedState(indexedFirst, indexedItem, 9);
                firstProperties.set(Map.of("country", "US", "host", "first"));
                secondProperties.set(Map.of("country", "CN", "host", "second"));
                assertThat(first.reportProperties(Map.of("country", "US"))).isTrue();
                assertThat(second.reportProperties()).isTrue();
                awaitRuntimeProperties(group, firstId, adapter, firstProperties.get());
                awaitRuntimeProperties(group, secondId, adapter, secondProperties.get());
                assertCountryExecutors(taskId, Map.of("CN", Map.of("executor", secondId, "host", "second"), "US", Map.of("executor", firstId, "host", "first")));
                assertFiniteCountryExecutor(indexedSecond, "CN", secondId, "second");
                assertThat(taskCatalog.loadTaskAllocationDescriptors(List.of(indexedFirst, indexedSecond)).values())
                        .allSatisfy(rule -> assertThat(rule.refill()).extracting(RefillTarget::poolName).containsOnly("country"));
                assertThat(first.snapshot().workerId()).isEqualTo(firstId);
                assertThat(second.snapshot().workerId()).isEqualTo(secondId);
                verify(preparationService, times(2)).prepareAll(eq(group), any(), any(), anyList());
            }
        }
    }

    @Test
    void realWorkersServeTwoTasksFromTheSameRefilledEligibility() throws Exception {
        String group="shared-eligibility-boundary";
        String event="extension.worker.shared.executor";
        assertThat(send("POST","/api/v1/worker-groups/"+group+":register",Jsons.toJson(Map.of("eventCodes", List.of(event)))).statusCode()).isEqualTo(200);
        var host=new AtomicReference<JavaWorker>();
        var handler=WorkerEventDefinition.extension("shared.executor",WorkerEventParameterResolvers.jsonMap(),
                ignored -> Jsons.toJson(Map.of("executor", host.get().snapshot().workerId())));
        try(var worker=JavaWorker.create(URI.create("http://127.0.0.1:"+port),group,"shared-host",WorkerTransportType.WEBSOCKET,
                () -> Map.of("testBucket", "red"),List.of(handler),WorkerConnectionOptions.of(Duration.ofSeconds(2),connectionPolicy()))) {
            host.set(worker); worker.start();
            awaitCondition(() -> worker.snapshot().workerId()!=null);
            String workerId=worker.snapshot().workerId();
            awaitRuntimeProperties(group,workerId,WEBSOCKET_ENDPOINT_MANAGER_ID,Map.of("testBucket", "red"));
            assertThat(send("POST","/api/v1/tasks",Jsons.toJson(Map.of("workerGroupId", group, "refill", List.of(Map.of("poolName",BucketPoolFixture.ID,"target", Map.of("workerId", List.of(workerId)), "count", 1))))).statusCode()).isEqualTo(400);
            var tasks=new LinkedHashMap<String,List<String>>();
            for(int t=0;t<2;t++) {
                var response=send("POST","/api/v1/tasks",Jsons.toJson(Map.of("workerGroupId", group, "refill", List.of(Map.of("poolName",BucketPoolFixture.ID,"target", Map.of("test.bucket", List.of("red")), "count", t+1)))));
                assertThat(response.statusCode()).isEqualTo(200);
                String task=JSON.readTree(response.body()).get("taskId").asText();
                String rejectedId=UUID.randomUUID().toString();
                var rejected=send("POST","/api/v1/tasks/"+task+"/items",Jsons.toJson(List.of(Map.of("messageId", rejectedId, "eventCode", event, "payload", Map.of(), "workerSelector", Map.of("executorName", BucketPoolFixture.ID, "input", Map.of("workerId", List.of(workerId)))))));
                assertThat(rejected.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(rejected.body()).get(rejectedId).get("status").asText()).isEqualTo("rejected");
                var missing=send("POST","/api/v1/tasks/"+task+"/items:states",Jsons.toJson(List.of(rejectedId)));
                assertThat(missing.statusCode()).isEqualTo(200); assertThat(JSON.readTree(missing.body()).get(rejectedId).isNull()).isTrue();
                var items=new ArrayList<Map<String,Object>>(); var ids=new ArrayList<String>();
                for(int i=0;i<6;i++) {
                    String id=UUID.randomUUID().toString();ids.add(id);
                    items.add(Map.of("messageId", id, "eventCode", event, "payload", Map.of(), "workerSelector", Map.of("executorName", BucketPoolFixture.ID, "input", Map.of("test.bucket", List.of("red")))));
                }
                assertThat(send("POST","/api/v1/tasks/"+task+"/items",Jsons.toJson(items)).statusCode()).isEqualTo(200);
                tasks.put(task,ids);
            }
            for(String task:tasks.keySet())assertThat(send("POST","/api/v1/tasks/"+task+"/approve",null).statusCode()).isEqualTo(200);
            for(var task:tasks.entrySet()) {
                awaitTaskExport(task.getKey());
                var response=send("POST","/api/v1/tasks/"+task.getKey()+"/results:load",Jsons.toJson(task.getValue()));
                var results=JSON.readTree(response.body());
                for(String id:task.getValue()) {
                    assertThat(results.get(id).get("status").asText()).isEqualTo("succeeded");
                    assertThat(Jsons.parseObject(results.get(id).get("opaqueResultPayload").asText())).containsEntry("executor",workerId);
                }
            }
            assertThat(taskCatalog.loadTaskAllocationDescriptors(List.copyOf(tasks.keySet())).values())
                    .allSatisfy(binding -> assertThat(binding.refill()).extracting(RefillTarget::poolName).containsOnly(BucketPoolFixture.ID));
            verify(preparationService,times(1)).prepareAll(eq(group),any(),any(),anyList());
        }
    }

    @Test
    void identityHintsTraverseRealMatchingDispatchWorkerAndResultWithoutHistoricalFence() throws Exception {
        String group = "identity-hint-boundary", event = "extension.worker.identity.hint";
        assertThat(send("POST", "/api/v1/worker-groups/" + group + ":register",
                Jsons.toJson(Map.of("eventCodes", List.of(event)))).statusCode()).isEqualTo(200);
        var host = new AtomicReference<JavaWorker>();
        var handler = WorkerEventDefinition.extension("identity.hint", WorkerEventParameterResolvers.jsonMap(),
                ignored -> Jsons.toJson(Map.of("executor", host.get().snapshot().workerId())));
        try (var worker = JavaWorker.create(URI.create("http://127.0.0.1:" + port), group, "hint-host",
                WorkerTransportType.WEBSOCKET, () -> Map.of("fixture", "identity-hint"), List.of(handler),
                WorkerConnectionOptions.of(Duration.ofSeconds(2), connectionPolicy()))) {
            host.set(worker);
            worker.start();
            awaitCondition(() -> worker.snapshot().workerId() != null);
            String workerId = worker.snapshot().workerId();
            awaitRuntimeProperties(group, workerId, WEBSOCKET_ENDPOINT_MANAGER_ID, Map.of("fixture", "identity-hint"));
            var created = send("POST", "/api/v1/tasks", Jsons.toJson(Map.of("workerGroupId", group, "refill", List.of(Map.of("poolName",IdentityHintPoolFixture.ID,"target", Map.of(), "count", 1)))));
            assertThat(created.statusCode()).isEqualTo(200);
            String task = JSON.readTree(created.body()).get("taskId").asText();
            var ids = new ArrayList<String>();
            var items = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < 3; i++) {
                String id = UUID.randomUUID().toString();
                ids.add(id);
                items.add(Map.of("messageId", id, "eventCode", event, "payload", Map.of(), "workerSelector", Map.of("executorName", IdentityHintPoolFixture.ID, "input", Map.of())));
            }
            assertThat(send("POST", "/api/v1/tasks/" + task + "/items", Jsons.toJson(items)).statusCode()).isEqualTo(200);
            assertThat(send("POST", "/api/v1/tasks/" + task + "/approve", null).statusCode()).isEqualTo(200);
            awaitTaskExport(task);
            var response = send("POST", "/api/v1/tasks/" + task + "/results:load", Jsons.toJson(ids));
            assertThat(response.statusCode()).isEqualTo(200);
            var results = JSON.readTree(response.body());
            for (String id : ids) {
                assertThat(results.get(id).get("status").asText()).isEqualTo("succeeded");
                assertThat(Jsons.parseObject(results.get(id).get("opaqueResultPayload").asText())).containsEntry("executor", workerId);
            }
            assertThat(identityHintRule.hintsReturned()).isGreaterThanOrEqualTo(ids.size());
        }
    }

    @Test
    void identityAndPhoneExecuteEmptySupplyTasksWithoutPoolStock() throws Exception {
        String group = "direct-query-boundary", event = "extension.worker.direct.query", phone = "+8613800000000";
        assertThat(send("POST", "/api/v1/worker-groups/" + group + ":register",
                Jsons.toJson(Map.of("eventCodes", List.of(event)))).statusCode()).isEqualTo(200);
        var host = new AtomicReference<JavaWorker>();
        var handler = WorkerEventDefinition.extension("direct.query", WorkerEventParameterResolvers.jsonMap(),
                ignored -> Jsons.toJson(Map.of("executor", host.get().snapshot().workerId())));
        Map<String, String> facts = Map.of("phone", phone, "messaging.enabled", "false");
        try (var worker = JavaWorker.create(URI.create("http://127.0.0.1:" + port), group, "direct-query-host",
                WorkerTransportType.WEBSOCKET, () -> facts, List.of(handler),
                WorkerConnectionOptions.of(Duration.ofSeconds(2), connectionPolicy()))) {
            host.set(worker); worker.start(); awaitCondition(() -> worker.snapshot().workerId() != null);
            String workerId = worker.snapshot().workerId();
            awaitRuntimeProperties(group, workerId, WEBSOCKET_ENDPOINT_MANAGER_ID, facts);
            for (String function : List.of("workerId", "worker.phone")) {
                awaitCondition(() -> {
                    var score = readScores(redisWitness.sync(), REDIS_KEYSPACE, group, List.of(workerId)).get(workerId);
                    return score != null && hasPolarity(score, WorkerScorePolarity.HOT_ACQUIRE)
                            && timeMillis(score) < slotStart(System.currentTimeMillis());
                });
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> matchingCatalog.take(group,
                        Map.of("pool", new com.xa.mass.kernel.assignment.WorkerQuery("worker.any", Map.of()))))
                        .isInstanceOf(IllegalArgumentException.class);
                var created = send("POST", "/api/v1/tasks", Jsons.toJson(Map.of("workerGroupId", group, "refill", List.of())));
                assertThat(created.statusCode()).isEqualTo(200);
                String task = JSON.readTree(created.body()).get("taskId").asText(), id = UUID.randomUUID().toString();
                var item = Map.of("messageId", id, "eventCode", event, "payload", Map.of(), "workerSelector", Map.of("executorName", function, "input", function.equals("workerId") ? workerId : phone));
                var appended = send("POST", "/api/v1/tasks/" + task + "/items", Jsons.toJson(List.of(item)));
                assertThat(appended.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(appended.body()).get(id).get("status").asText()).isEqualTo("applied");
                assertThat(send("POST", "/api/v1/tasks/" + task + "/approve", null).statusCode()).isEqualTo(200);
                awaitTaskExport(task);
                var loaded = send("POST", "/api/v1/tasks/" + task + "/results:load", Jsons.toJson(List.of(id)));
                var result = JSON.readTree(loaded.body()).get(id);
                assertThat(result.get("status").asText()).isEqualTo("succeeded");
                assertThat(Jsons.parseObject(result.get("opaqueResultPayload").asText())).containsEntry("executor", workerId);
            }
            verify(matchingCatalog, org.mockito.Mockito.never()).refill(eq(group), anyList(), anyList());
        }
    }

    @Test
    void separateSupplierAndConsumerUseTwoFunctionsOverOneMessagingPool() throws Exception {
        String group="shared-pool-functions", event="extension.worker.pool.share", phone="+8613800000001";
        assertThat(send("POST","/api/v1/worker-groups/"+group+":register",Jsons.toJson(Map.of("eventCodes",List.of(event)))).statusCode()).isEqualTo(200);
        var host=new AtomicReference<JavaWorker>();
        var handler=WorkerEventDefinition.extension("pool.share",WorkerEventParameterResolvers.jsonMap(),
                ignored->Jsons.toJson(Map.of("executor",host.get().snapshot().workerId())));
        var facts=Map.of("phone",phone,"country","CN","messaging.enabled","true");
        try(var worker=JavaWorker.create(URI.create("http://127.0.0.1:"+port),group,"shared-pool-host",WorkerTransportType.WEBSOCKET,
                ()->facts,List.of(handler),WorkerConnectionOptions.of(Duration.ofSeconds(2),connectionPolicy()))) {
            host.set(worker);worker.start();awaitCondition(()->worker.snapshot().workerId()!=null);
            String workerId=worker.snapshot().workerId();awaitRuntimeProperties(group,workerId,WEBSOCKET_ENDPOINT_MANAGER_ID,facts);
            var supplied=send("POST","/api/v1/tasks",Jsons.toJson(Map.of("workerGroupId",group,"refill",List.of(Map.of("poolName","messaging","target",Map.of(),"count",1)))));
            String supplier=JSON.readTree(supplied.body()).get("taskId").asText();
            // An unresolved identity keeps the supplier active while its demand drives stock.
            String pending=UUID.randomUUID().toString();
            assertThat(send("POST","/api/v1/tasks/"+supplier+"/items",Jsons.toJson(List.of(Map.of("messageId",pending,"eventCode",event,
                    "payload",Map.of(),"workerSelector",Map.of("executorName","workerId","input","absent-worker"))))).statusCode()).isEqualTo(200);
            assertThat(send("POST","/api/v1/tasks/"+supplier+"/approve",null).statusCode()).isEqualTo(200);
            for(String function:List.of("proof.messaging.country","proof.messaging.phones")) {
                var consumer=send("POST","/api/v1/tasks",Jsons.toJson(Map.of("workerGroupId",group,"refill",List.of())));
                String task=JSON.readTree(consumer.body()).get("taskId").asText(), id=UUID.randomUUID().toString();
                assertThat(taskCatalog.loadTaskAllocationDescriptors(List.of(task)).get(task).refill()).isEmpty();
                Object input=function.endsWith("country")?"CN":List.of(phone);
                assertThat(send("POST","/api/v1/tasks/"+task+"/items",Jsons.toJson(List.of(Map.of("messageId",id,"eventCode",event,"payload",Map.of(),
                        "workerSelector",Map.of("executorName",function,"input",input))))).statusCode()).isEqualTo(200);
                assertThat(send("POST","/api/v1/tasks/"+task+"/approve",null).statusCode()).isEqualTo(200);
                awaitTaskExport(task);
                var observed=JSON.readTree(send("POST","/api/v1/tasks/"+task+"/results:load",Jsons.toJson(List.of(id))).body()).get(id);
                assertThat(observed.get("status").asText()).isEqualTo("succeeded");
                assertThat(Jsons.parseObject(observed.get("opaqueResultPayload").asText())).containsEntry("executor",workerId);
            }
            assertThat(send("POST","/api/v1/tasks/"+supplier+"/close",null).statusCode()).isEqualTo(200);
            assertThat(matchingCatalog.normalizeQuery(group,new com.xa.mass.kernel.assignment.WorkerQuery("proof.messaging.country","CN")).input()).isEqualTo("CN");
            // Closing supply neither unregisters functions nor forces a stock clear.
            verify(matchingCatalog,org.mockito.Mockito.atLeastOnce()).refill(eq(group),anyList(),anyList());
        }
    }

    @Test
    void groupBatchesServeMultipleRulesAndSharedTasksThroughActualWorkers() throws Exception {
        String groupA="group-refill-a",groupB="group-refill-b",event="extension.worker.group.refill";
        for(String group:List.of(groupA,groupB))assertThat(send("POST","/api/v1/worker-groups/"+group+":register",
                Jsons.toJson(Map.of("eventCodes", List.of(event)))).statusCode()).isEqualTo(200);
        var workers=new ArrayList<JavaWorker>();
        var identities=new LinkedHashMap<String,Map<String,String>>();
        try {
            for(int i=0;i<6;i++) {
                String group=i<4?groupA:groupB;
                Map<String,String> facts=Map.of("country", i<2?"CN":"US", "messaging.enabled", i%2==0?"true":"false", "phone", "+1202555000"+i);
                var host=new AtomicReference<JavaWorker>();
                var handler=WorkerEventDefinition.extension("group.refill",WorkerEventParameterResolvers.jsonMap(),
                        ignored->Jsons.toJson(Map.of("executor", host.get().snapshot().workerId())));
                var worker=JavaWorker.create(URI.create("http://127.0.0.1:"+port),group,"group-refill-host-"+i,
                        WorkerTransportType.WEBSOCKET,()->facts,List.of(handler),
                        WorkerConnectionOptions.of(Duration.ofSeconds(2),connectionPolicy()));
                workers.add(worker);host.set(worker);worker.start();
                awaitCondition(()->worker.snapshot().workerId()!=null);
                String workerId=worker.snapshot().workerId();
                awaitRuntimeProperties(group,workerId,WEBSOCKET_ENDPOINT_MANAGER_ID,facts);
                var identity=new LinkedHashMap<>(facts);identity.put("group",group);identities.put(workerId,Map.copyOf(identity));
            }
            var tasks=new LinkedHashMap<String,List<String>>();
            var taskRules=new LinkedHashMap<String,String>();
            var taskGroups=new LinkedHashMap<String,String>();
            for(int t=0;t<4;t++) {
                String group=t==3?groupB:groupA;
                String rule=t<2?"worker.country":t==2?"worker.messaging.available":"worker.any";
                Map<String,Object> query=t<2?Map.of("worker.country", List.of("CN")):Map.of();
                var response=send("POST","/api/v1/tasks",Jsons.toJson(Map.of("workerGroupId", group, "refill", List.of(Map.of("poolName",poolName(rule),"target", query, "count", t<2?t+1:2)))));
                assertThat(response.statusCode()).isEqualTo(200);
                String task=JSON.readTree(response.body()).get("taskId").asText();
                taskGroups.put(task,group);taskRules.put(task,rule);
                var ids=new ArrayList<String>();
                for(int page=0;page<2;page++) {
                    var items=new ArrayList<Map<String,Object>>();
                    for(int i=0;i<100;i++) {
                        String id=UUID.randomUUID().toString();ids.add(id);
                        Map<String,Object> selector=Map.of("executorName", rule, "input", t<2?List.of("CN"):Map.of());
                        items.add(Map.of("messageId", id, "eventCode", event, "payload", Map.of(), "workerSelector", selector));
                    }
                    var appended=send("POST","/api/v1/tasks/"+task+"/items",Jsons.toJson(items));
                    assertThat(appended.statusCode()).isEqualTo(200);
                    var effects=JSON.readTree(appended.body());
                    for(var item:items)assertThat(effects.get((String)item.get("messageId")).get("status").asText()).isEqualTo("applied");
                }
                tasks.put(task,List.copyOf(ids));
            }
            var multiRuleRoot=new java.util.concurrent.atomic.AtomicBoolean();
            var multiGroupRoot=new java.util.concurrent.atomic.AtomicBoolean();
            var supplied=java.util.concurrent.ConcurrentHashMap.<String>newKeySet();
            doAnswer(call->{
                Map<String,List<RefillTarget>> targets=call.getArgument(0);
                var rules=targets.get(groupA);
                if(rules!=null && rules.stream().anyMatch(t->t.poolName().equals("messaging"))
                        && rules.stream().filter(t->t.poolName().equals("country")).count()==2)multiRuleRoot.set(true);
                if(targets.containsKey(groupA) && targets.containsKey(groupB))multiGroupRoot.set(true);
                return call.callRealMethod();
            }).when(matchingCatalog).groupsNeedingRefill(anyMap());
            doAnswer(call->{
                String group=call.getArgument(0);
                List<HeldCandidate> offered=call.getArgument(2);
                if(Set.of(groupA,groupB).contains(group)) {
                    supplied.add(group);
                    // Proof-only read: production still carries the fence without reading it back.
                    var states=readWorkerFences(group,offered.stream().map(h -> h.workerId()).toList());
                    for(var held:offered) {
                        assertThat(identities.get(held.workerId())).containsEntry("group",group);
                        assertThat(states.get(held.workerId())).isEqualTo(held.score());
                        assertThat(mark(states.get(held.workerId()))).isZero();
                    }
                }
                return call.callRealMethod();
            }).when(matchingCatalog).refill(anyString(),anyList(),anyList());
            for(String task:tasks.keySet())assertThat(send("POST","/api/v1/tasks/"+task+"/approve",null).statusCode()).isEqualTo(200);
            var pending=new LinkedHashMap<String,List<String>>();tasks.forEach((task,ids)->pending.put(task,new ArrayList<>(ids)));
            long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while(!pending.isEmpty() && System.nanoTime()<deadline) {
                for(String task:List.copyOf(pending.keySet())) {
                    var ids=List.copyOf(pending.get(task).subList(0,Math.min(100,pending.get(task).size())));
                    var response=send("POST","/api/v1/tasks/"+task+"/results:load",Jsons.toJson(ids));
                    assertThat(response.statusCode()).isEqualTo(200);
                    var results=JSON.readTree(response.body());
                    for(String id:ids) {
                        var result=results.get(id);
                        if("not_observed".equals(result.get("status").asText()))continue;
                        assertThat(result.get("status").asText()).isEqualTo("succeeded");
                        var identity=identities.get(Jsons.parseObject(result.get("opaqueResultPayload").asText()).get("executor"));
                        assertThat(identity).containsEntry("group",taskGroups.get(task));
                        if("worker.country".equals(taskRules.get(task)))assertThat(identity).containsEntry("country","CN");
                        if("worker.messaging.available".equals(taskRules.get(task)))assertThat(identity).containsEntry("messaging.enabled","true");
                        pending.get(task).remove(id);
                    }
                    if(pending.get(task).isEmpty())pending.remove(task);
                }
                if(!pending.isEmpty())Thread.sleep(50);
            }
            assertThat(pending).as("all 800 Items close with qualified actual executors").isEmpty();
            assertThat(multiRuleRoot.get()).isTrue();assertThat(multiGroupRoot.get()).isTrue();
            assertThat(supplied).containsExactlyInAnyOrder(groupA,groupB);
            for(String task:tasks.keySet())awaitTaskExport(task);
        } finally {
            doCallRealMethod().when(matchingCatalog).groupsNeedingRefill(anyMap());
            doCallRealMethod().when(matchingCatalog).refill(anyString(),anyList(),anyList());
            for(var worker:workers)worker.close();
        }
    }

    private String createIndexedTask(String group) throws Exception {
        var created = send("POST", "/api/v1/tasks", Jsons.toJson(Map.of("workerGroupId", group, "refill", List.of(Map.of("poolName","country","target", Map.of("worker.country", List.of("CN")), "count", 1)))));
        assertThat(created.statusCode()).isEqualTo(200);
        return JSON.readTree(created.body()).get("taskId").asText();
    }

    private String assertFiniteCountryExecutor(String taskId, String country, String workerId, String host) throws Exception {
        String messageId = UUID.randomUUID().toString();
        var appended = send("POST", "/api/v1/tasks/"+taskId+"/items", Jsons.toJson(List.of(Map.of("messageId", messageId, "eventCode", "extension.worker.country.executor", "payload", Map.of(), "workerSelector", Map.of("executorName", "worker.country", "input", List.of(country))))));
        assertThat(appended.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(appended.body()).get(messageId).get("status").asText()).isEqualTo("applied");
        assertThat(send("POST", "/api/v1/tasks/"+taskId+"/approve", null).statusCode()).isEqualTo(200);
        long deadline = System.nanoTime() + RESULT_CONVERGENCE_TIMEOUT.toNanos();
        boolean observed = false;
        while (System.nanoTime() < deadline) {
            var response = send("POST", "/api/v1/tasks/"+taskId+"/results:load", Jsons.toJson(List.of(messageId)));
            if (response.statusCode() == 200 && "succeeded".equals(JSON.readTree(response.body()).get(messageId).get("status").asText())) {
                observed = true;
                break;
            }
            Thread.sleep(20);
        }
        assertThat(observed).isTrue();
        for (int i=0; i<2; i++) {
            var loaded = send("POST", "/api/v1/tasks/"+taskId+"/results:load", Jsons.toJson(List.of(messageId)));
            var result = JSON.readTree(loaded.body()).get(messageId);
            assertThat(Jsons.parseObject(result.get("opaqueResultPayload").asText()))
                    .containsEntry("executor", workerId).containsEntry("host", host);
        }
        awaitTaskExport(taskId);
        var states = send("POST", "/api/v1/tasks/"+taskId+"/items:states", Jsons.toJson(List.of(messageId)));
        assertThat(states.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(states.body()).get(messageId).get("tag").asInt()).isEqualTo(6);
        assertThat(send("POST", "/api/v1/tasks/"+taskId+"/close", null).statusCode()).isEqualTo(200);
        return messageId;
    }

    private void assertCountryExecutors(String taskId, Map<String, Map<String, String>> expectedByCountry) throws Exception {
        var items = new ArrayList<Map<String, Object>>();
        var expected = new LinkedHashMap<String, Map<String, String>>();
        expectedByCountry.forEach((country, executor) -> {
            String messageId = UUID.randomUUID().toString();
            expected.put(messageId, executor);
            items.add(Map.of("messageId", messageId, "eventCode", "extension.worker.country.executor", "payload", Map.of(), "workerSelector", Map.of("executorName", "worker.country", "input", List.of(country))));
        });
        var response = send("POST", "/api/v1/tasks/" + taskId + "/items:call", Jsons.toJson(Map.of("items", items, "waitTimeoutMillis", 10000)));
        assertThat(response.statusCode()).isEqualTo(200);
        var results = JSON.readTree(response.body());
        expected.forEach((id, executor) -> {
            var result = results.get(id);
            assertThat(result.get("status").asText()).isEqualTo("succeeded");
            assertThat(Jsons.parseObject(result.get("opaqueResultPayload").asText())).containsAllEntriesOf(executor);
        });
    }

    @Test
    void lostFullPropertiesPublicationIsReplacedByNextIncrementalObservationOnBothTextProtocols() throws Exception {
        for (WorkerTransportType type : List.of(WorkerTransportType.WEBSOCKET, WorkerTransportType.SOCKET)) {
            String groupId = "lost-properties-" + UUID.randomUUID();
            String adapterId = type == WorkerTransportType.WEBSOCKET
                    ? WEBSOCKET_ENDPOINT_MANAGER_ID : SOCKET_ENDPOINT_MANAGER_ID;
            assertThat(send("POST", "/api/v1/worker-groups/" + groupId + ":register",
                    "{\"eventCodes\":[]}").statusCode()).isEqualTo(200);
            Map<String, String> original = Map.of("network.type", "wifi", "battery", "80", "ssid", "lab");
            AtomicReference<Map<String, String>> host = new AtomicReference<>(original);
            try (JavaWorker worker = JavaWorker.create(URI.create("http://127.0.0.1:" + port),
                    groupId, "lost-full-host", type, host::get)) {
                worker.start();
                awaitCondition(() -> worker.snapshot().workerId() != null);
                String workerId = worker.snapshot().workerId();
                URI endpoint = worker.snapshot().endpointUri();
                awaitRuntimeProperties(groupId, workerId, adapterId, original);
                assertAdapterProperties(adapterId, workerId, original);
                assertThat(send("PATCH", "/api/v1/worker-groups/" + groupId + "/workers/"
                        + workerId + "/platform-properties", "{\"pool\":\"retained\"}").statusCode()).isEqualTo(200);

                Map<String, String> lostFull = Map.of("network.type", "cellular", "battery", "88");
                CountDownLatch failedSubmission = new CountDownLatch(1);
                AtomicInteger submissions = new AtomicInteger();
                doAnswer(invocation -> {
                    Map<String, Map<String, String>> snapshots = invocation.getArgument(1);
                    if (lostFull.equals(snapshots.get(workerId)) && submissions.incrementAndGet() == 1) {
                        failedSubmission.countDown();
                        throw new IllegalStateException("Matching facts unavailable");
                    }
                    return invocation.callRealMethod();
                }).when(matchingCatalog).upsertWorkerFactsBatch(eq(groupId), anyMap());

                host.set(lostFull);
                assertThat(worker.reportProperties()).isTrue();
                assertThat(failedSubmission.await(5, TimeUnit.SECONDS)).isTrue();
                assertAdapterProperties(adapterId, workerId, lostFull);
                Thread.sleep(150); // No SYSTEM replay across several configured Report backoffs.
                assertThat(submissions).hasValue(1);
                assertThat(matchingCatalog.loadWorkerFacts(groupId, List.of(workerId)).get(workerId)
                        .workerProperties()).isEqualTo(original);
                awaitRuntimeProperties(groupId, workerId, adapterId, original);

                Map<String, String> latest = Map.of("network.type", "cellular", "battery", "89");
                host.set(latest);
                assertThat(worker.reportProperties(Map.of("battery", "89"))).isTrue();
                awaitRuntimeProperties(groupId, workerId, adapterId, latest);
                assertAdapterProperties(adapterId, workerId, latest);
                verify(matchingCatalog).upsertWorkerFactsBatch(groupId, Map.of(workerId, latest));
                var facts = matchingCatalog.loadWorkerFacts(groupId, List.of(workerId)).get(workerId);
                assertThat(facts.workerProperties()).isEqualTo(latest).doesNotContainKey("ssid");
                assertThat(facts.platformProperties()).isEqualTo(Map.of("pool", "retained"));

                long holdUntil = System.currentTimeMillis() + 60_000;
                long observed = readWorkerFences(groupId, List.of(workerId)).get(workerId);
                workerScores.acquireObservedHotScoreLeases(groupId, Map.of(workerId, observed), holdUntil);
                long held = readWorkerFences(groupId, List.of(workerId)).get(workerId);
                assertThat(worker.snapshot().workerId()).isEqualTo(workerId);
                assertThat(worker.snapshot().endpointUri()).isEqualTo(endpoint);
                verify(preparationService, times(1)).prepareAll(eq(groupId), any(), any(), anyList());
            } finally {
                doCallRealMethod().when(matchingCatalog).upsertWorkerFactsBatch(eq(groupId), anyMap());
            }
        }
    }

    private void assertAdapterProperties(String adapterId, String workerId, Map<String, String> expected)
            throws Exception {
        var response = send("POST", "/api/v1/worker-delivery/endpoint-managers/" + adapterId + "/direct-calls",
                JSON.writeValueAsString(Map.of("messageType", "platform.adapter.worker-properties.snapshot", "opaquePayload", workerIdsPayload(workerId), "waitTimeoutMillis", 3_000)));
        Map<String, Object> payload = Jsons.parseObject(observedDirectPayload(response, adapterId, "platform.adapter.command.succeeded"));
        Map<?, ?> observations = (Map<?, ?>) payload.get("propertiesByWorkerId");
        Map<?, ?> observation = (Map<?, ?>) observations.get(workerId);
        assertThat(observation.get("properties")).isEqualTo(expected);
    }

    private void awaitRuntimeProperties(String groupId, String workerId, String adapterId,
                                        Map<String, String> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            HttpResponse<String> response = send("POST", "/api/v1/runtime-view/worker-groups/"
                    + groupId + "/workers:preview", "100");
            assertThat(response.statusCode()).isEqualTo(200);
            List<?> workers = (List<?>) Jsons.parseObject(response.body()).get("workers");
            for (Object entry : workers) {
                Map<?, ?> view = (Map<?, ?>) entry;
                if (!workerId.equals(view.get("workerId"))) continue;
                assertThat(view.get("endpointManagerId")).isEqualTo(adapterId);
                if (expected.equals(view.get("workerProperties"))) {
                    return;
                }
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Runtime Worker Properties did not converge");
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Runtime Properties boundary condition was not established");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void explicitAnyPoolClosesThroughTheJavaPollingWorkerWithoutMatchingFacts()
            throws Exception {
        runWorkerGroupTaskCall(TransportProfile.POLLING);
    }

    @Test
    void workerCommandFailureDoesNotFinalizeTheItemOnAnyTransport() throws Exception {
        for (TransportProfile profile : TransportProfile.values()) {
            String suffix = UUID.randomUUID().toString();
            String group = "failure-event-" + suffix;
            String key = "worker-" + suffix;
            String messageId = "item-" + suffix;
            var registered = send("POST", "/api/v1/worker-groups/" + group + ":register",
                    Jsons.toJson(Map.of("eventCodes", List.of(TEST_EVENT_CODE))));
            assertThat(registered.statusCode()).isEqualTo(200);
            String taskId = JSON.readTree(registered.body()).get("taskId").asText();
            var prepared = prepareWorker(group, key, profile, Map.of());
            AtomicInteger executions = new AtomicInteger();
            CountDownLatch secondEntered = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            List<WorkerEventDefinition<?>> definitions = List.of(WorkerEventDefinition.extension(
                    TEST_CAPABILITY, WorkerEventParameterResolvers.jsonMap(), input -> {
                        if (executions.incrementAndGet() == 1) {
                            throw new IllegalStateException("scripted first execution failure");
                        }
                        secondEntered.countDown();
                        if (!finish.await(15, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test completion gate expired");
                        }
                        return TEST_RESULT;
                    }));
            RunningWorker worker = profile == TransportProfile.POLLING
                    ? new PollingWorkerHandle(new PollingWorkerTransport(
                            new OkHttpWorkerPointClient(prepared.endpointUri(), Duration.ofSeconds(2)),
                            prepared.workerId(), WorkerCommandDispatcher.forWorker(definitions)))
                    : startTextMessageWorker(group, key, prepared.workerId(), Map.of(), definitions,
                            profile == TransportProfile.WEBSOCKET
                                    ? WorkerTransportType.WEBSOCKET : WorkerTransportType.SOCKET);
            try {
                var submitted = send("POST", "/api/v1/tasks/" + taskId + "/items:call",
                        Jsons.toJson(Map.of("items", List.of(Map.of("messageId", messageId, "eventCode", TEST_EVENT_CODE, "payload", Map.of("value", "input"), "workerSelector", Map.of("executorName", "workerId", "input", prepared.workerId()))), "waitTimeoutMillis", 1)));
                assertThat(submitted.statusCode()).isEqualTo(200);
                assertThat(secondEntered.await(15, TimeUnit.SECONDS)).isTrue();
                verify(taskEvidence, org.mockito.Mockito.atLeastOnce()).appendTaskEvidence(
                        eq(com.xa.mass.kernel.delivery.TaskEvidenceRuntime.TaskEvidenceType.EXECUTION_FAILURE),
                        org.mockito.ArgumentMatchers.argThat(reports -> reports.stream().anyMatch(report ->
                                prepared.workerId().equals(report.sourceId())
                                        && "platform.worker.command.failed".equals(report.messageType()))));
                var unobserved = send("POST", "/api/v1/tasks/" + taskId + "/results:load",
                        Jsons.toJson(List.of(messageId)));
                assertThat(JSON.readTree(unobserved.body()).get(messageId).get("status").asText())
                        .isEqualTo("not_observed");
                finish.countDown();
                awaitStoredResult(taskId, messageId);
                assertStoredItemAndFinalSuccess(taskId, messageId);
            } finally {
                finish.countDown();
                worker.close();
            }
        }
    }

    @Test
    void registeredTaskCallWaitsForWebSocketAndSocketWorkerResults()
            throws Exception {
        var path = java.nio.file.Files.createTempFile("xa-mass-task-observation-", ".jfr");
        try (var recording = new jdk.jfr.Recording()) {
            for (String event : List.of("xa.mass.HttpInitial", "xa.mass.HttpCompletion", "xa.mass.TaskSubmission",
                    "xa.mass.TaskStorage", "xa.mass.TaskDispatch", "xa.mass.TaskResult", "xa.mass.TaskRpc")) recording.enable(event);
            recording.start();
            runWorkerGroupTaskCall(TransportProfile.WEBSOCKET);
            runWorkerGroupTaskCall(TransportProfile.SOCKET);
            List<jdk.jfr.consumer.RecordedEvent> events;
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            do {
                recording.dump(path);
                events = jdk.jfr.consumer.RecordingFile.readAllEvents(path);
                if (events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpCompletion")
                        && e.getString("operation").equals("ITEMS_CALL")).count() == 4) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            var completions = events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpCompletion")
                    && e.getString("operation").equals("ITEMS_CALL")).toList();
            assertThat(completions).hasSize(4).allMatch(e -> e.getInt("httpStatus") == 200);
            assertThat(events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpInitial")
                    && e.getString("operation").equals("ITEMS_CALL")))
                    .hasSize(4).allMatch(e -> e.getBoolean("virtualThread")
                            == environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false));
            assertThat(events.stream().filter(e -> e.hasField("stage")).map(e -> e.getString("stage")))
                    .contains("ITEM_STORED", "ITEM_INITIALIZED", "COMMAND_PUBLISHED", "RESULT_STORED", "OBSERVED");
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @Test
    void sharedRuleTasksUseCanonicalFactsAndRemainIndependentAfterClosure()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "property-tools-boundary";
        String clientWorkerKey = "property-worker-" + suffix;

        assertThat(send(
                "POST",
                "/api/v1/worker-groups/" + workerGroupId + ":register",
                """
                        {
                          "eventCodes": ["%s"]
                        }
                        """.formatted(TEST_EVENT_CODE)
        ).statusCode()).isEqualTo(200);
        PreparedCoordinate boundWorker = prepareWorker(
                workerGroupId,
                clientWorkerKey,
                TransportProfile.WEBSOCKET,
                Map.of("region", "cn-east", "proofPool", "A")
        );
        String workerId = boundWorker.workerId();

        RunningWorker worker = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("region", "cn-east", "proofPool", "A"),
                TransportProfile.WEBSOCKET
        );
        try {
            awaitWorkerProperties(workerGroupId, workerId);
            assertThat(send(
                    "PATCH",
                    "/api/v1/worker-groups/" + workerGroupId
                            + "/workers/" + workerId
                            + "/platform-properties",
                    "{\"proofEnabled\":\"yes\"}"
            ).statusCode()).isEqualTo(200);
            String firstMessageId = "property-message-1-" + suffix;
            String secondMessageId = "property-message-2-" + suffix;
            Map<String,Object> selector=Map.of("executorName", "proof.worker.facts", "input", Map.of("proofPool", "A", "proofEnabled", "yes"));
            String taskId=createTask(workerGroupId,"proof.worker.facts");
            String otherTaskId=createTask(workerGroupId,"proof.worker.facts");
            var sharedRules = taskCatalog.loadTaskAllocationDescriptors(List.of(taskId, otherTaskId));
            assertThat(sharedRules.get(taskId)).isNotNull();
            assertThat(sharedRules.get(taskId).refill()).isEqualTo(sharedRules.get(otherTaskId).refill());
            assertThat(sharedRules.get(taskId).refill()).extracting(RefillTarget::poolName).doesNotContain(taskId, otherTaskId);
            appendItem(taskId, firstMessageId, selector);
            appendItem(taskId, secondMessageId, selector);
            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/approve",
                    null
            ).statusCode()).isEqualTo(200);

            awaitStoredResult(taskId, firstMessageId);
            awaitStoredResult(taskId, secondMessageId);

            HttpResponse<String> exported = awaitTaskExport(taskId);
            assertThat(exported.headers().firstValue("Content-Type"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .startsWith("application/x-ndjson"));
            Map<String, String> exportedResults = new LinkedHashMap<>();
            for (String line : exported.body().lines().toList()) {
                var row = JSON.readTree(line);
                exportedResults.put(
                        row.get("messageId").asText(),
                        row.get("opaqueResultPayload").stringValue()
                );
            }
            assertThat(exportedResults).containsExactlyInAnyOrderEntriesOf(
                    Map.of(firstMessageId, TEST_RESULT, secondMessageId, TEST_RESULT)
            );

            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/close",
                    null
            ).statusCode()).isEqualTo(200);
            // The second Task already shared the Rule before the first closed.
            // Its independent append, approval and execution still work afterwards.
            assertThat(taskCatalog.loadTaskAllocationDescriptors(List.of(taskId, otherTaskId)).values())
                    .containsExactlyInAnyOrderElementsOf(sharedRules.values());
            appendItem(otherTaskId, "after-other-task-closed", selector);
            assertThat(send("POST", "/api/v1/tasks/" + otherTaskId + "/approve", null).statusCode()).isEqualTo(200);
            awaitStoredResult(otherTaskId, "after-other-task-closed");
            awaitTaskExport(otherTaskId);
            assertThat(send("POST", "/api/v1/tasks/" + otherTaskId + "/close", null).statusCode()).isEqualTo(200);
        } finally {
            worker.close();
        }
    }

    @Autowired
    private org.springframework.core.env.Environment environment;

    @Test
    void actualHttpExecutionAndAsyncCompletionAreObservedWithoutChangingTheDirectResponse() throws Exception {
        var path = java.nio.file.Files.createTempFile("xa-mass-http-diagnostic-", ".jfr");
        try (var recording = new jdk.jfr.Recording()) {
            recording.enable("xa.mass.HttpInitial");
            recording.enable("xa.mass.HttpCompletion");
            recording.enable("xa.mass.HttpExecutor").withPeriod(Duration.ofMillis(100));
            recording.start();
            observedDirectPayload(adapterDirectCall("platform.adapter.events.snapshot", "null"), WEBSOCKET_ENDPOINT_MANAGER_ID, "platform.adapter.command.succeeded");
            // Servlet completion may follow the client's receipt. Await diagnostic completion only, with a fixed budget.
            List<jdk.jfr.consumer.RecordedEvent> events = List.of();
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            do {
                recording.dump(path);
                events = jdk.jfr.consumer.RecordingFile.readAllEvents(path);
                if (events.stream().anyMatch(e -> e.getEventType().getName().equals("xa.mass.HttpCompletion")
                        && e.getString("operation").equals("DIRECT_CALL"))
                        && events.stream().anyMatch(e -> e.getEventType().getName().equals("xa.mass.HttpExecutor"))) break;
                Thread.sleep(50);
            } while (System.nanoTime() < deadline);
            var initial = events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpInitial")
                    && e.getString("operation").equals("DIRECT_CALL")).toList();
            var completed = events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpCompletion")
                    && e.getString("operation").equals("DIRECT_CALL")).toList();
            assertThat(initial).hasSize(1);
            assertThat(completed).hasSize(1);
            boolean virtual = environment.getProperty("spring.threads.virtual.enabled", Boolean.class, false);
            assertThat(initial.getFirst().getBoolean("virtualThread")).isEqualTo(virtual);
            assertThat(completed.getFirst().getInt("httpStatus")).isEqualTo(200);
            var executor = events.stream().filter(e -> e.getEventType().getName().equals("xa.mass.HttpExecutor")).findFirst().orElseThrow();
            assertThat(executor.getBoolean("platformPool")).isEqualTo(!virtual);
            assertThat(executor.getInt("maximum")).isEqualTo(virtual ? -1 : 200);
            int expectedMinimum = virtual ? -1
                    : environment.getProperty("server.tomcat.threads.min-spare", Integer.class, 10);
            assertThat(executor.getInt("minimum")).isEqualTo(expectedMinimum);
        } finally { java.nio.file.Files.deleteIfExists(path); }
    }

    @Test
    void directWorkerAndAdapterCallsTraverseWebSocketAdapterWithoutPause()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "direct-tools-" + suffix;
        String clientWorkerKey = "direct-worker-" + suffix;

        assertThat(send(
                "POST",
                "/api/v1/worker-groups/" + workerGroupId + ":register",
                """
                        {
                          "eventCodes": ["%s", "%s"]
                        }
                        """.formatted(TEST_EVENT_CODE, DIRECT_EVENT_CODE)
        ).statusCode()).isEqualTo(200);
        PreparedCoordinate boundWorker = prepareWorker(
                workerGroupId,
                clientWorkerKey,
                TransportProfile.WEBSOCKET,
                Map.of("runtime", "java-direct")
        );
        String workerId = boundWorker.workerId();
        RunningWorker worker = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("runtime", "java-direct"),
                TransportProfile.WEBSOCKET
        );
        try {
            awaitWorkerRegistered(workerGroupId, workerId);
            assertThat(observedDirectPayload(workerDirectCall(
                    workerGroupId,
                    workerId,
                    DIRECT_EVENT_CODE,
                    "{}"
            ), workerId, "platform.worker.command.succeeded"))
                    .isEqualTo("{\"direct\":\"observed\"}");

            assertThat(Jsons.parseObject(
                    observedDirectPayload(adapterDirectCall(
                                    "platform.adapter.probe",
                                    "null"
                            ), WEBSOCKET_ENDPOINT_MANAGER_ID, "platform.adapter.command.succeeded")
            )).containsEntry("adapterId", WEBSOCKET_ENDPOINT_MANAGER_ID)
                    .containsEntry("reachable", true);

            assertThat(Jsons.parseObject(
                    observedDirectPayload(workerDirectCall(
                            workerGroupId,
                            workerId,
                            WorkerManagementEventDefinitions.PROBE_EVENT,
                            "null"
                    ), workerId, "platform.worker.command.succeeded")
            )).containsEntry("reachable", true);

            assertThat(Jsons.parseObject(
                    observedDirectPayload(workerDirectCall(
                            workerGroupId,
                            workerId,
                            WorkerManagementEventDefinitions
                                    .PROPERTIES_SNAPSHOT_EVENT,
                            "null"
                    ), workerId, "platform.worker.command.succeeded")
            )).containsEntry(
                    "properties",
                    Map.of("runtime", "java-direct")
            );

            observedDirectPayload(workerDirectCall(workerGroupId, workerId,
                    "extension.worker.not-installed", "{}"), workerId,
                    "platform.worker.command.failed");
            observedDirectPayload(adapterDirectCall("platform.adapter.not-installed", "null"),
                    WEBSOCKET_ENDPOINT_MANAGER_ID, "platform.adapter.command.failed");

            assertConnectionState(workerId, "CONNECTED");
            assertWorkerProperties(workerId);
            assertThat(Jsons.parseObject(
                    observedDirectPayload(adapterDirectCall(
                                    "platform.adapter.worker-connections.close-current",
                                    workerIdsPayload(workerId)
                            ), WEBSOCKET_ENDPOINT_MANAGER_ID, "platform.adapter.command.succeeded")
            )).containsEntry(
                    "outcomeByWorkerId",
                    Map.of(workerId, "close-started")
            );

            assertThat(Jsons.parseObject(
                    observedDirectPayload(workerDirectCall(
                            workerGroupId,
                            workerId,
                            WorkerManagementEventDefinitions.PROBE_EVENT,
                            "null"
                    ), workerId, "platform.worker.command.succeeded")
            )).containsEntry("reachable", true);
            assertConnectionState(workerId, "CONNECTED");
            assertWorkerProperties(workerId);
        } finally {
            worker.close();
        }
    }

    @Test
    void exactRouteEvidenceConvergesWorkerScoreThroughKernelResultPacer()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = SERVICEABILITY_WORKER_GROUP_ID;
        String clientWorkerKey = "serviceability-worker-" + suffix;

        assertThat(send(
                "POST",
                "/api/v1/worker-groups/" + workerGroupId + ":register",
                """
                        {
                          "eventCodes": ["%s"]
                        }
                        """.formatted(TEST_EVENT_CODE)
        ).statusCode()).isEqualTo(200);
        PreparedCoordinate boundWorker = prepareWorker(
                workerGroupId,
                clientWorkerKey,
                TransportProfile.WEBSOCKET,
                Map.of("runtime", "serviceability-e2e")
        );
        String workerId = boundWorker.workerId();
        awaitWorkerRegistered(workerGroupId, workerId);

        Long initial = awaitWorkerScore(
                workerGroupId,
                workerId,
                WorkerScorePolarity.RECOVERY_RECHECK
        );
        assertThat(timeMillis(initial)).isEqualTo(100);

        assertThat(mark(initial)).isZero();
        assertThat(workerScores.observeDueHotScoreCandidates(workerGroupId, null, 100)).isEmpty();

        RunningWorker first = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("runtime", "serviceability-e2e"),
                TransportProfile.WEBSOCKET
        );
        RunningWorker reconnected = null;
        String demandTaskId = null;
        boolean demandTaskCreated = false;
        try {
            awaitConnectionState(workerId, "CONNECTED");
            Long connected = awaitWorkerScore(
                    workerGroupId,
                    workerId,
                    WorkerScorePolarity.HOT_ACQUIRE
            );
            assertThat(timeMillis(connected))
                    .isGreaterThanOrEqualTo(timeMillis(initial));

            first.close();
            first = null;
            awaitConnectionState(workerId, "DISCONNECTED");
            Long disconnected = awaitWorkerScore(
                    workerGroupId,
                    workerId,
                    WorkerScorePolarity.RECOVERY_RECHECK
            );
            assertThat(timeMillis(disconnected))
                    .isGreaterThanOrEqualTo(timeMillis(connected));

            long reconnectEvidenceFloor = slotStart(System.currentTimeMillis());
            reconnected = startWorker(
                    workerGroupId,
                    clientWorkerKey,
                    workerId,
                    boundWorker.endpointUri(),
                    Map.of("runtime", "serviceability-e2e"),
                    TransportProfile.WEBSOCKET
            );
            awaitConnectionState(workerId, "CONNECTED");
            Long restored = awaitWorkerScore(
                    workerGroupId,
                    workerId,
                    WorkerScorePolarity.HOT_ACQUIRE
            );
            assertThat(timeMillis(restored))
                    .isGreaterThanOrEqualTo(Math.max(
                            timeMillis(disconnected),
                            reconnectEvidenceFloor
                    ));

            demandTaskId=createTask(workerGroupId,"worker.any");
            demandTaskCreated = true;
            // Establish due work before approval; an approved empty finite Task can close immediately.
            appendItem(
                    demandTaskId,
                    "serviceability-demand-item-" + suffix,
                    Map.of("executorName", "workerId", "input", "missing-worker-"+suffix)
            );
            awaitServiceabilitySnapshot(workerGroupId, workerId, demandTaskId);
        } finally {
            if (demandTaskCreated) {
                send(
                        "POST",
                        "/api/v1/tasks/" + demandTaskId + "/close",
                        null
                );
            }
            if (first != null) {
                first.close();
            }
            if (reconnected != null) {
                reconnected.close();
            }
        }
    }

    private HttpResponse<String> workerDirectCall(
            String workerGroupId,
            String workerId,
            String eventCode,
            String opaquePayload
    ) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workerGroupId", workerGroupId);
        request.put("workerPayloads", Map.of(workerId, opaquePayload));
        request.put("messageType", eventCode);
        request.put("waitTimeoutMillis", 3_000);
        return send(
                "POST",
                "/api/v1/worker-delivery/endpoint-managers/"
                        + WEBSOCKET_ENDPOINT_MANAGER_ID
                        + "/direct-calls",
                JSON.writeValueAsString(request)
        );
    }

    private HttpResponse<String> adapterDirectCall(
            String eventCode,
            String opaquePayload
    ) throws Exception {
        return send(
                "POST",
                "/api/v1/worker-delivery/endpoint-managers/"
                        + WEBSOCKET_ENDPOINT_MANAGER_ID
                        + "/direct-calls",
                JSON.writeValueAsString(Map.of("messageType", eventCode, "opaquePayload", opaquePayload, "waitTimeoutMillis", 3_000))
        );
    }

    private static String observedDirectPayload(
            HttpResponse<String> response,
            String targetId,
            String messageType
    ) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        var target = JSON.readTree(response.body())
                .get("results")
                .get(targetId);
        assertThat(target.get("status").asText()).isEqualTo("observed");
        assertThat(target.get("messageType").asText())
                .isEqualTo(messageType);
        assertThat(target.get("diagnosticCode").isTextual()).isTrue();
        return target.get("opaqueResultPayload").asText();
    }

    private void assertConnectionState(String workerId, String state)
            throws Exception {
        assertThat(connectionState(workerId)).isEqualTo(state);
    }

    private String connectionState(String workerId) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/runtime-view/endpoint-managers/"
                        + WEBSOCKET_ENDPOINT_MANAGER_ID
                        + "/workers:network-observe",
                JSON.writeValueAsString(List.of(workerId))
        );
        assertThat(response.statusCode()).isEqualTo(200);
        var payload = JSON.readTree(response.body());
        assertThat(payload.get("endpointManagerId").asText())
                .isEqualTo(WEBSOCKET_ENDPOINT_MANAGER_ID);
        assertThat(payload.get("readAt").asText()).isNotBlank();
        return payload.get("statesByWorkerId")
                .get(workerId)
                .asText()
                .toUpperCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private void assertWorkerProperties(String workerId) throws Exception {
        Map<String, Object> payload = Jsons.parseObject(
                observedDirectPayload(adapterDirectCall(
                                "platform.adapter.worker-properties.snapshot",
                                workerIdsPayload(workerId)
                        ), WEBSOCKET_ENDPOINT_MANAGER_ID, "platform.adapter.command.succeeded")
        );
        Map<String, Object> propertiesByWorkerId =
                (Map<String, Object>) payload.get(
                        "propertiesByWorkerId"
                );
        Map<String, Object> observation =
                (Map<String, Object>) propertiesByWorkerId.get(workerId);
        assertThat(observation)
                .doesNotContainKeys(
                        "workerGroupId",
                        "connectionState",
                        "freshness",
                        "version",
                        "observedAtMillis"
                )
                .containsEntry(
                        "properties",
                        Map.of("runtime", "java-direct")
                );
        assertThat(observation.get("updatedAtMillis")).isNotNull();
    }

    private static String workerIdsPayload(String workerId) {
        return Jsons.toJson(Map.of("workerIds", List.of(workerId)));
    }

    private void awaitServiceabilitySnapshot(
            String workerGroupId,
            String workerId,
            String demandTaskId
    ) throws Exception {
        Long before = awaitWorkerScore(
                workerGroupId,
                workerId,
                WorkerScorePolarity.HOT_ACQUIRE
        );
        // Establish the exact RECOVERY fixture before Task approval exposes
        // this Group to periodic probes. Never overwrite an in-flight hold.
        assertThat(workerScores.toggleCurrentPolarity(
                workerGroupId, workerId, before
        ).status()).isEqualTo(WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED);
        Long recovery = awaitWorkerScore(
                workerGroupId, workerId, WorkerScorePolarity.RECOVERY_RECHECK
        );
        assertThat(timeMillis(recovery)).isEqualTo(timeMillis(before));
        assertThat(send(
                "POST", "/api/v1/tasks/" + demandTaskId + "/approve", null
        ).statusCode()).isEqualTo(200);
        Long after = awaitWorkerScore(
                workerGroupId,
                workerId,
                WorkerScorePolarity.HOT_ACQUIRE,
                SERVICEABILITY_CONVERGENCE_TIMEOUT
        );
        // The explicit missing target cannot hold this Worker. A fresh RECOVERY
        // coordinate may still occupy the current slot at the first scan. The
        // next eligible round schedules its next recheck before offering a probe.
        assertThat(timeMillis(after)).isGreaterThan(
                timeMillis(before)
        );

    }

    private void awaitConnectionState(String workerId, String expectedState)
            throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (expectedState.equals(connectionState(workerId))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "Worker connection state did not become " + expectedState
        );
    }

    private Map<String, Long> readWorkerFences(String group, List<String> ids) {
        return readScores(redisWitness.sync(), REDIS_KEYSPACE, group, ids);
    }

    private Long awaitWorkerScore(
            String workerGroupId,
            String workerId,
            WorkerScorePolarity expectedPolarity
    ) throws InterruptedException {
        return awaitWorkerScore(
                workerGroupId,
                workerId,
                expectedPolarity,
                Duration.ofSeconds(5)
        );
    }

    private Long awaitWorkerScore(
            String workerGroupId,
            String workerId,
            WorkerScorePolarity expectedPolarity,
            Duration maximumWait
    ) throws InterruptedException {
        long deadline = System.nanoTime()
                + maximumWait.toNanos();
        Long lastObserved = null;
        while (System.nanoTime() < deadline) {
            Long state = readWorkerFences(
                    workerGroupId,
                    List.of(workerId)
            ).get(workerId);
            lastObserved = state;
            // Keep polarity and field witnesses tied to this one Redis observation.
            if (state != null && hasPolarity(state, expectedPolarity)) {
                return state;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "Worker score did not become " + expectedPolarity
                        + " within " + maximumWait + "; last observed: " + lastObserved
        );
    }

    @Test
    void javaServerOwnsPacerLifecycleAndReadiness() throws Exception {
        KernelPacerAssembly.Snapshot snapshot = kernelPacerAssembly.snapshot();
        assertThat(snapshot.enabled()).isTrue();
        assertThat(snapshot.runtime().resultConvergenceState())
                .isEqualTo("RUNNING");
        assertThat(snapshot.runtime().dispatchConvergenceState())
                .isEqualTo("RUNNING");
        assertThat(kernelPacerAssembly.isRunning()).isTrue();
        HttpResponse<String> readiness = send(
                "GET",
                "/actuator/health/readiness",
                null
        );
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void trackedObservationsCloseThroughActualWorkersAndExistingQueryApis() throws Exception {
        for (TransportProfile profile : TransportProfile.values()) {
            String group = "tracked-" + UUID.randomUUID();
            String clientKey = "tracked-worker";
            var registered = send("POST", "/api/v1/worker-groups/" + group + ":register",
                    Jsons.toJson(Map.of("eventCodes", List.of(TEST_EVENT_CODE))));
            assertThat(registered.statusCode()).isEqualTo(200);
            String taskId = JSON.readTree(registered.body()).get("taskId").asText();
            var prepared = prepareWorker(group, clientKey, profile, Map.of("runtime", "tracked"));
            AtomicReference<WorkerOutcomeReporter> retained = new AtomicReference<>();
            List<WorkerEventDefinition<?>> definitions = List.of(WorkerEventDefinition.extension(
                    TEST_CAPABILITY, WorkerEventParameterResolvers.jsonMap(),
                    (payload, reporter) -> { retained.set(reporter); return TEST_RESULT; }));
            RunningWorker worker = profile == TransportProfile.POLLING
                    ? new PollingWorkerHandle(new PollingWorkerTransport(
                            new OkHttpWorkerPointClient(URI.create("http://127.0.0.1:" + port), Duration.ofSeconds(2)),
                            prepared.workerId(), WorkerCommandDispatcher.forWorker(definitions)))
                    : startTextMessageWorker(group, clientKey, prepared.workerId(), Map.of("runtime", "tracked"),
                            definitions, profile == TransportProfile.WEBSOCKET ? WorkerTransportType.WEBSOCKET : WorkerTransportType.SOCKET);
            try {
                awaitWorkerRegistered(group, prepared.workerId());
                assertTaskCallSucceeded(taskId, prepared.workerId(), "sent-item");
                var reporter = retained.get();
                assertThat(reporter).isNotNull();
                long time = System.currentTimeMillis();
                for (int tag : List.of(7, 8)) {
                    assertThat(reporter.report(tag, time, null)).isTrue();
                    awaitTrackedState(taskId, "sent-item", tag);
                }
                assertThat(reporter.report(9, time + 1, "first reply")).isTrue();
                awaitTrackedState(taskId, "sent-item", 9);
                assertThat(reporter.report(9, time + 2, "latest reply")).isTrue();
                long deadline = System.nanoTime() + RESULT_CONVERGENCE_TIMEOUT.toNanos();
                boolean observed = false;
                while (System.nanoTime() < deadline) {
                    var loaded = send("POST", "/api/v1/tasks/" + taskId + "/results:load", "[\"sent-item\"]");
                    if (loaded.statusCode() == 200 && "latest reply".equals(JSON.readTree(loaded.body())
                            .get("sent-item").path("opaqueResultPayload").asText())) {
                        observed = true;
                        break;
                    }
                    Thread.sleep(20);
                }
                assertThat(observed).isTrue();
                assertTaskCallSucceeded(taskId, prepared.workerId(), "next-item");
                var repeated = send("POST", "/api/v1/tasks/" + taskId + "/results:load", "[\"sent-item\"]");
                assertThat(JSON.readTree(repeated.body()).get("sent-item").get("opaqueResultPayload").asText())
                        .isEqualTo("latest reply");
                assertThat(JSON.readTree(repeated.body()).get("sent-item").has("observedAtMillis")).isFalse();
            } finally {
                worker.close();
            }
            assertThat(retained.get().report(9, System.currentTimeMillis(), "after stop")).isFalse();
        }
    }

    private void awaitTrackedState(String taskId, String itemId, int tag) throws Exception {
        long deadline = System.nanoTime() + RESULT_CONVERGENCE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            var response = send("POST", "/api/v1/tasks/" + taskId + "/items:states", Jsons.toJson(List.of(itemId)));
            if (response.statusCode() == 200) {
                var state = JSON.readTree(response.body()).get(itemId);
                if (state != null && !state.isNull() && state.get("tag").asInt() == tag) {
                    assertThat(state.get("band").asText()).isEqualTo("terminal");
                    assertThat(state.get("outcomeName").asText())
                            .isEqualTo(Map.of(7, "delivered", 8, "read", 9, "replied").get(tag));
                    assertThat(state.has("score")).isFalse();
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("TRACKED Item did not reach tag " + tag);
    }

    private void runWorkerGroupTaskCall(
            TransportProfile transportProfile
    ) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "task-call-tools-" + suffix;
        String clientWorkerKey = "task-call-worker-" + suffix;

        HttpResponse<String> registration = send(
                "POST",
                "/api/v1/worker-groups/" + workerGroupId + ":register",
                """
                        {
                          "eventCodes": ["%s"]
                        }
                        """.formatted(TEST_EVENT_CODE)
        );
        assertThat(registration.statusCode()).isEqualTo(200);
        assertThat(registration.body()).contains("\"status\":\"registered\"");
        String taskId = JSON.readTree(registration.body())
                .get("taskId")
                .asText();
        assertThat(taskId).isNotBlank();
        HttpResponse<String> taskPreview = send(
                "POST",
                "/api/v1/runtime-view/tasks:preview",
                "100"
        );
        assertThat(taskPreview.statusCode()).isEqualTo(200);
        var matchingManagedTaskIds = new ArrayList<String>();
        for (var entry : JSON.readTree(taskPreview.body()).get("entries")) {
            var task = entry.get("task");
            var workerGroup = entry.get("workerGroup");
            if (task == null || task.isNull()
                    || workerGroup == null || workerGroup.isNull()) {
                continue;
            }
            if (workerGroupId.equals(task.get("workerGroupId").asText())
                    && workerGroupId.equals(
                            workerGroup.get("workerGroupId").asText()
                    )
                    && task.get("refill").isEmpty()
                    && "PARK_WHEN_IDLE".equals(
                            task.get("idleDisposition").asText()
                    )
                    && entry.get("taskId").asText().equals(
                            task.get("taskId").asText()
                    )) {
                matchingManagedTaskIds.add(entry.get("taskId").asText());
            }
        }
        assertThat(matchingManagedTaskIds).containsExactly(taskId);

        PreparedCoordinate boundWorker = prepareWorker(
                workerGroupId,
                clientWorkerKey,
                transportProfile,
                Map.of("runtime", "java-task-call")
        );
        String workerId = boundWorker.workerId();
        RunningWorker worker = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("runtime", "java-task-call"),
                transportProfile
        );
        try {
            awaitWorkerRegistered(workerGroupId, workerId);
            String firstMessageId = "task-call-message-1-" + suffix;
            String secondMessageId = "task-call-message-2-" + suffix;
            assertTaskCallSucceeded(
                    taskId,
                    workerId,
                    firstMessageId
            );
            assertTaskCallSucceeded(
                    taskId,
                    workerId,
                    secondMessageId
            );

            HttpResponse<String> loaded = send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/results:load",
                    "[\"" + firstMessageId + "\"]"
            );
            assertThat(loaded.statusCode()).isEqualTo(200);
            var loadedResult = JSON.readTree(loaded.body())
                    .get(firstMessageId);
            assertThat(loadedResult.get("status").asText())
                    .isEqualTo("succeeded");
            assertThat(loadedResult.get("opaqueResultPayload").asText())
                    .isEqualTo(TEST_RESULT);

            HttpResponse<String> repeatedRegistration = send(
                    "POST",
                    "/api/v1/worker-groups/" + workerGroupId + ":register",
                    """
                            {
                              "eventCodes": ["%s"]
                            }
                            """.formatted(TEST_EVENT_CODE)
            );
            assertThat(repeatedRegistration.statusCode()).isEqualTo(200);
            assertThat(repeatedRegistration.body())
                    .contains("\"status\":\"already_registered\"");
            assertThat(JSON.readTree(repeatedRegistration.body())
                    .get("taskId")
                    .asText()).isEqualTo(taskId);
            HttpResponse<String> managedClose = send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/close",
                    null
            );
            assertThat(managedClose.statusCode()).isEqualTo(400);
            assertThat(managedClose.body()).contains("\"code\":12008");
            if (transportProfile == TransportProfile.POLLING) {
                assertThat(matchingCatalog.loadWorkerFacts(workerGroupId, List.of(workerId)))
                        .containsEntry(workerId, null);
            }
        } finally {
            worker.close();
        }
    }

    private void assertTaskCallSucceeded(
            String taskId,
            String workerId,
            String messageId
    ) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/tasks/" + taskId + "/items:call",
                """
                        {
                          "items": [{
                            "messageId": "%s",
                            "eventCode": "%s",
                            "payload": {"value": "input"},
                            "workerSelector": {"executorName":"workerId","input":"%s"}
                          }],
                          "waitTimeoutMillis": 10000
                        }
                        """.formatted(
                        messageId,
                        TEST_EVENT_CODE,
                        workerId
                )
        );
        assertThat(response.statusCode()).isEqualTo(200);
        var itemResult = JSON.readTree(response.body())
                .get(messageId);
        assertThat(itemResult.get("status").asText())
                .isEqualTo("succeeded");
        assertThat(itemResult
                .get("opaqueResultPayload")
                .stringValue()).isEqualTo(TEST_RESULT);
    }

    private void assertStoredItemAndFinalSuccess(
            String taskId,
            String messageId
    ) throws Exception {
        RedisClient client = RedisClient.create(REDIS_URL);
        try (var connection = client.connect(StringCodec.UTF8)) {
            var redis = connection.sync();
            assertThat(redis.hexists(
                    REDIS_KEYSPACE.base()
                            + ":task:" + taskId + ":items",
                    messageId
            )).isTrue();
            Double score = redis.zscore(
                    REDIS_KEYSPACE.base()
                            + ":task:" + taskId + ":item_score",
                    messageId
            );
            assertThat(score).isNotNull();
            assertThat(score.longValue()
                    / TaskItemScoreBandCore.TAG_FACTOR)
                    .isEqualTo(6);
        } finally {
            client.shutdown();
        }
        var response = send("POST", "/api/v1/tasks/" + taskId + "/items:states",
                Jsons.toJson(List.of(messageId, "missing-item")));
        assertThat(response.statusCode()).isEqualTo(200);
        var states = JSON.readTree(response.body());
        assertThat(states.get(messageId).get("band").asText()).isEqualTo("terminal");
        assertThat(states.get(messageId).get("tag").asInt()).isEqualTo(6);
        assertThat(states.get(messageId).get("outcomeName").asText()).isEqualTo("succeeded");
        assertThat(states.get(messageId).has("score")).isFalse();
        assertThat(states.get("missing-item").isNull()).isTrue();
    }

    @AfterAll
    void stopRuntimeOwnersAndCleanExactRedisScopes() {
        try {
            try {
                workerAssemblyLifecycleHost.stop();
            } finally {
                try {
                    taskRpcResultProbe.stop();
                } finally {
                    kernelPacerAssembly.stop();
                }
            }
        } finally {
            cleanExactRedisScopes();
        }
    }

    private static void cleanExactRedisScopes() {
        RedisClient client = RedisClient.create(REDIS_URL);
        try (var connection = client.connect(StringCodec.UTF8)) {
            var redis = connection.sync();
            try {
                assertThat(redis.hget(
                        OUTSIDE_SENTINEL_KEY,
                        "runtime-boundary"
                )).isEqualTo("untouched");
            } finally {
                REDIS_TEST_SCOPE.cleanup(redis);
                OUTSIDE_REDIS_TEST_SCOPE.cleanup(redis);
            }
        } finally {
            client.shutdown();
        }
    }

    private void appendItem(
            String taskId,
            String messageId,
            Map<String,Object> selector
    ) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/tasks/" + taskId + "/items",
                JSON.writeValueAsString(List.of(Map.of("messageId", messageId, "eventCode", TEST_EVENT_CODE, "payload", Map.of("value", "input"), "workerSelector", selector)))
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"applied\"");
    }

    private RunningWorker startWorker(
            String workerGroupId,
            String clientWorkerKey,
            String workerId,
            URI serverUrl,
            Map<String, String> workerProperties,
            TransportProfile transportProfile
    ) throws Exception {
        List<WorkerEventDefinition<?>> definitions =
                List.of(
                        WorkerEventDefinition.extension(
                                TEST_CAPABILITY,
                                WorkerEventParameterResolvers.jsonMap(),
                                payload ->
                                Jsons.toJson(
                                        Map.of("observed", payload.get("value"))
                                )
                        ),
                        WorkerEventDefinition.extension(
                                DIRECT_CAPABILITY,
                                WorkerEventParameterResolvers.jsonMap(),
                                payload -> "{\"direct\":\"observed\"}"
                        )
                );
        return switch (transportProfile) {
            case WEBSOCKET -> startTextMessageWorker(
                    workerGroupId,
                    clientWorkerKey,
                    workerId,
                    workerProperties,
                    definitions,
                    WorkerTransportType.WEBSOCKET
            );
            case SOCKET -> startTextMessageWorker(
                    workerGroupId,
                    clientWorkerKey,
                    workerId,
                    workerProperties,
                    definitions,
                    WorkerTransportType.SOCKET
            );
            case POLLING -> new PollingWorkerHandle(
                    new PollingWorkerTransport(
                            new OkHttpWorkerPointClient(
                                    serverUrl,
                                    Duration.ofSeconds(2)
                            ),
                            workerId,
                            WorkerCommandDispatcher.forWorker(definitions)
                    )
            );
        };
    }

    private RunningWorker startTextMessageWorker(
            String workerGroupId,
            String clientWorkerKey,
            String workerId,
            Map<String, String> workerProperties,
            List<WorkerEventDefinition<?>> definitions,
            WorkerTransportType transportType
    ) {
        JavaWorker worker = JavaWorker.create(
                URI.create("http://127.0.0.1:" + port),
                workerGroupId,
                clientWorkerKey,
                transportType,
                () -> workerProperties,
                definitions,
                WorkerConnectionOptions.of(
                        Duration.ofSeconds(2),
                        connectionPolicy()
                )
        );
        return new TextMessageWorkerHandle(worker);
    }

    private static TextMessageReconnectPolicy connectionPolicy() {
        return TextMessageReconnectPolicy.of(
                20,
                Duration.ofMillis(20),
                Duration.ofSeconds(1)
        );
    }

    private PreparedCoordinate prepareWorker(
            String workerGroupId,
            String clientWorkerKey,
            TransportProfile profile,
            Map<String, String> workerProperties
    ) throws Exception {
        Map<String, Object> completeProperties =
                new LinkedHashMap<>(workerProperties);
        completeProperties.put("clientWorkerKey", clientWorkerKey);
        String propertiesJson = JSON.writeValueAsString(
                completeProperties
        );
        String transportType = switch (profile) {
            case POLLING -> "POLLING";
            case WEBSOCKET -> "WEBSOCKET";
            case SOCKET -> "SOCKET";
        };
        HttpResponse<String> prepareResponse = send(
                "POST",
                "/api/v1/worker-groups/" + workerGroupId
                        + "/workers:prepare",
                "{\"transportType\":\"" + transportType
                        + "\",\"workerProperties\":"
                        + propertiesJson + "}"
        );
        assertThat(prepareResponse.statusCode()).isEqualTo(200);
        String workerId = JSON.readTree(prepareResponse.body())
                .get("workerId")
                .asText();
        URI endpointUri = URI.create(
                JSON.readTree(prepareResponse.body())
                        .get("endpointUri")
                        .asText()
        );
        return new PreparedCoordinate(workerId, endpointUri);
    }

    private void awaitWorkerRegistered(
            String workerGroupId,
            String workerId
    ) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send(
                    "POST",
                    "/api/v1/runtime-view/worker-groups/" + workerGroupId + "/workers:preview",
                    "100"
            );
            if (response.statusCode() == 200) {
                for (var worker : JSON.readTree(response.body()).get("workers")) {
                    if (workerId.equals(worker.get("workerId").asText())) {
                        return;
                    }
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Worker Bind was not applied to Kernel");
    }

    private void awaitWorkerProperties(String workerGroupId, String workerId) throws InterruptedException {
        awaitCondition(() -> matchingCatalog.loadWorkerFacts(workerGroupId, List.of(workerId)).get(workerId) != null);
    }

    private void awaitStoredResult(
            String taskId,
            String messageId
    ) throws Exception {
        long deadline = System.nanoTime()
                + RESULT_CONVERGENCE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/results:load",
                    "[\"" + messageId + "\"]"
            );
            if (response.statusCode() == 200) {
                var result = JSON.readTree(response.body())
                        .get(messageId);
                if ("succeeded".equals(result.get("status").asText())
                        && TEST_RESULT.equals(result
                                .get("opaqueResultPayload")
                                .asText())) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("TaskItem success result was not stored");
    }

    private HttpResponse<String> awaitTaskExport(String taskId)
            throws Exception {
        long deadline = System.nanoTime()
                + RESULT_CONVERGENCE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/results:export",
                    null
            );
            if (response.statusCode() == 200) {
                return response;
            }
            if (response.statusCode() != 400
                    || JSON.readTree(response.body()).get("code").asInt()
                    != 12010) {
                throw new AssertionError(
                        "Task Result export returned an unexpected response"
                );
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Task Result export did not become ready");
    }

    private String createTask(
            String workerGroupId,
            String ruleId
    ) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/tasks",
                """
                {
                  "workerGroupId": "%s",
                  "refill": [{"poolName":"%s","target":{},"count":100}],
                  "priority": 0,
                  "maxRetryTimes": 3
                }
                """.formatted(
                workerGroupId,
                poolName(ruleId)
                )
        );
        assertThat(response.statusCode()).isEqualTo(200);
        String taskId = JSON.readTree(response.body())
                .get("taskId")
                .asText();
        assertThat(taskId).startsWith("task-");
        return taskId;
    }

    private HttpResponse<String> send(
            String method,
            String path,
            String body
    ) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                        body,
                        StandardCharsets.UTF_8
                );
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .method(method, publisher);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(
                    "Could not reserve an integration-test port",
                    error
            );
        }
    }

    private static int[] availablePorts(int count) {
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            int[] ports = new int[count];
            for (int index = 0; index < count; index++) {
                sockets[index] = new ServerSocket(0);
                ports[index] = sockets[index].getLocalPort();
            }
            return ports;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(
                    "Could not reserve integration-test Adapter ports",
                    error
            );
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket == null) {
                    continue;
                }
                try {
                    socket.close();
                } catch (java.io.IOException ignored) {
                    // Best-effort release during static test setup.
                }
            }
        }
    }

    private static void addWebSocketAdapter(
            DynamicPropertyRegistry registry,
            String adapterId,
            int listenPort
    ) {
        String prefix = "xa.mass.worker-delivery.adapter.instances."
                + adapterId;
        registry.add(prefix + ".type", () -> "WEBSOCKET");
        registry.add(prefix + ".listen-host", () -> "127.0.0.1");
        registry.add(
                prefix + ".listen-port",
                () -> Integer.toString(listenPort)
        );
        addAdapterConfig(registry, prefix);
        registry.add("xa.mass.worker-endpoints.defaults.WEBSOCKET", () -> adapterId);
        String endpointPrefix = "xa.mass.worker-endpoints.endpoints."
                + adapterId;
        registry.add(
                endpointPrefix + ".transport-type",
                () -> "WEBSOCKET"
        );
        registry.add(
                endpointPrefix + ".public-uri",
                () -> "ws://127.0.0.1:" + listenPort
                        + "/api/v1/worker-delivery/websocket"
        );
    }

    private static void addSocketAdapter(
            DynamicPropertyRegistry registry,
            String adapterId,
            int listenPort
    ) {
        String prefix = "xa.mass.worker-delivery.adapter.instances."
                + adapterId;
        registry.add(prefix + ".type", () -> "SOCKET");
        registry.add(prefix + ".listen-host", () -> "127.0.0.1");
        registry.add(
                prefix + ".listen-port",
                () -> Integer.toString(listenPort)
        );
        addAdapterConfig(registry, prefix);
        registry.add("xa.mass.worker-endpoints.defaults.SOCKET", () -> adapterId);
        String endpointPrefix = "xa.mass.worker-endpoints.endpoints."
                + adapterId;
        registry.add(
                endpointPrefix + ".transport-type",
                () -> "SOCKET"
        );
        registry.add(
                endpointPrefix + ".public-uri",
                () -> "tcp://127.0.0.1:" + listenPort
        );
    }

    private static void addAdapterConfig(
            DynamicPropertyRegistry registry,
            String prefix
    ) {
        registry.add(prefix + ".command-backoff", () -> "20ms");
        registry.add(prefix + ".command-consume-limit", () -> "100");
        registry.add(prefix + ".command-retry-capacity", () -> "1000");
        registry.add(prefix + ".report-backoff", () -> "20ms");
        registry.add(prefix + ".report-queue-capacity", () -> "1000");
        registry.add(
                prefix + ".reconnect-verification-retention",
                () -> "10m"
        );
        registry.add(
                prefix + ".maximum-disconnected-workers",
                () -> "100000"
        );
        registry.add(
                prefix + ".maximum-encoded-properties-bytes",
                () -> "67108864"
        );
        registry.add(prefix + ".send-time-limit", () -> "5s");
        registry.add(prefix + ".shutdown-timeout", () -> "5s");
    }

    private interface RunningWorker extends AutoCloseable {

        @Override
        void close();
    }

    private record PreparedCoordinate(String workerId, URI endpointUri) {
    }

    private enum TransportProfile {
        POLLING,
        WEBSOCKET,
        SOCKET
    }

    private static final class PollingWorkerHandle
            implements RunningWorker {

        private final PollingWorkerTransport transport;
        private final Thread thread;
        private volatile String lastFailure = "none";
        private volatile int completedCalls;

        private PollingWorkerHandle(PollingWorkerTransport transport) {
            this.transport = transport;
            thread = Thread.ofPlatform()
                    .name("integration-polling-worker")
                    .daemon(true)
                    .start(() -> {
                        while (!Thread.currentThread().isInterrupted()) {
                            try {
                                if (!transport.runOnce()) {
                                    Thread.sleep(20);
                                } else {
                                    completedCalls++;
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            } catch (Exception error) {
                                lastFailure = error instanceof com.xa.mass.worker.error.WorkerException workerError
                                        ? workerError.operation() + ": " + workerError.errorCode().name()
                                        : error.getClass().getSimpleName();
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    });
        }

        @Override
        public void close() {
            if (completedCalls == 0) {
                System.err.println("Polling boundary made no progress; last failure: " + lastFailure);
            }
            transport.close();
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class TextMessageWorkerHandle
            implements RunningWorker {

        private final JavaWorker worker;

        private TextMessageWorkerHandle(JavaWorker worker) {
            this.worker = worker;
            try {
                worker.start();
            } catch (RuntimeException | Error failure) {
                worker.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            worker.close();
        }
    }
}
