package com.xa.mass.server.e2e.support;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.model.TaskStateValidationResult;
import com.xa.mass.runtime.api.ActiveLeaseRecord;
import com.xa.mass.runtime.api.TaskWorkStats;
import com.xa.mass.runtime.api.TaskWorkRuntime;
import com.xa.mass.storage.api.TaskShellStore;
import com.xa.mass.workerpack.sample.client.SampleWorkerClient;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.sdk.model.AdapterNodeRegistration;
import com.xa.mass.sdk.model.NodeGroupBindingRegistration;
import com.xa.mass.sdk.model.WorkerSnapshot;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerGroupDeclaration;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.transport.model.CanonicalWorkerRouteKeyCodec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public abstract class AbstractSampleE2eTest {
    private static final int MIN_TEST_WEBSOCKET_PORT = 20_000;
    private static final int MAX_TEST_WEBSOCKET_PORT = 65_000;

    private static final AtomicInteger NEXT_WEBSOCKET_PORT = new AtomicInteger(initialPortSeed());

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired(required = false)
    protected MassSdkApplication app;

    @Autowired
    protected TaskShellStore taskStorage;

    @Autowired
    protected TaskWorkRuntime taskWorkRuntime;

    protected static void registerWebSocketProperties(DynamicPropertyRegistry registry, int websocketPort) {
        registry.add("mass.websocket.port", () -> websocketPort);
    }

    protected static void registerWebSocketPropertiesWithClientUri(DynamicPropertyRegistry registry, int websocketPort) {
        registerWebSocketProperties(registry, websocketPort);
        registry.add("sample.client.websocket-uri", () -> "ws://127.0.0.1:" + websocketPort + "/ws");
    }

    protected static void registerJdbcStorageProperties(DynamicPropertyRegistry registry,
                                                        String mode,
                                                        String jdbcUrl,
                                                        String username,
                                                        String password) {
        registry.add("mass.storage.mode", () -> mode);
        registry.add("mass.storage.jdbc.url", () -> jdbcUrl);
        registry.add("mass.storage.jdbc.username", () -> username);
        registry.add("mass.storage.jdbc.password", () -> password);
    }

    protected static String isolatedH2JdbcUrl(String testId) {
        return "jdbc:h2:mem:" + testId + "_" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
    }

    protected static int findFreePort() {
        while (true) {
            int candidate = NEXT_WEBSOCKET_PORT.getAndUpdate(AbstractSampleE2eTest::nextCandidatePort);
            try (ServerSocket socket = new ServerSocket(candidate)) {
                return socket.getLocalPort();
            } catch (IOException ignored) {
                // Try the next candidate. Tests run in one JVM, so bounded monotonic
                // allocation still avoids immediate reuse without overflowing port range.
            }
        }
    }

    private static int initialPortSeed() {
        try (ServerSocket socket = new ServerSocket(0)) {
            int allocated = socket.getLocalPort();
            return allocated >= MIN_TEST_WEBSOCKET_PORT && allocated <= MAX_TEST_WEBSOCKET_PORT
                    ? allocated
                    : MIN_TEST_WEBSOCKET_PORT;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate initial free port seed", e);
        }
    }

    private static int nextCandidatePort(int current) {
        if (current < MIN_TEST_WEBSOCKET_PORT || current >= MAX_TEST_WEBSOCKET_PORT) {
            return MIN_TEST_WEBSOCKET_PORT;
        }
        return current + 1;
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> exchange(String path, HttpMethod method, Object body) {
        String url = "http://127.0.0.1:" + port + path;
        ResponseEntity<Map> response = restTemplate.exchange(url, method, new HttpEntity<>(body), Map.class);
        return response.getBody();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> exchange(String path, HttpMethod method, Object body, HttpHeaders headers) {
        String url = "http://127.0.0.1:" + port + path;
        ResponseEntity<Map> response = restTemplate.exchange(url, method, new HttpEntity<>(body, headers), Map.class);
        return response.getBody();
    }

    protected void declareExternalWorkerGroup(String groupId,
                                              String projectCode,
                                              String eventCode,
                                              HttpHeaders headers) {
        assertApiOk(exchange("/worker-api/v1/worker-groups", HttpMethod.POST, Map.of(
                "groupId", groupId,
                "eventBindings", List.of(Map.of(
                        "eventCode", eventCode,
                        "projectCodes", List.of(projectCode)
                ))
        ), headers));
    }

    protected void bindExternalAdapterNode(String adapterNodeId, String workerGroupId, HttpHeaders headers) {
        assertApiOk(exchange("/worker-api/v1/adapter-nodes", HttpMethod.POST, Map.of(
                "adapterNodeId", adapterNodeId,
                "adapterType", "external",
                "endpointId", adapterNodeId
        ), headers));
        assertApiOk(exchange("/worker-api/v1/node-group-bindings", HttpMethod.POST, Map.of(
                "adapterNodeId", adapterNodeId,
                "workerGroupId", workerGroupId
        ), headers));
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> task(Map<String, Object> response) {
        return (Map<String, Object>) responseData(response).get("task");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> messages(Map<String, Object> response) {
        return (List<Map<String, Object>>) responseData(response).get("messages");
    }

    protected TaskStateValidationResult validateTaskState(String taskId) {
        return requireSdkApp().taskDiagnostics().validateTaskState(taskId);
    }

    protected List<String> violations(TaskStateValidationResult validation) {
        return validation.getViolations() == null
                ? List.of()
                : validation.getViolations().stream().map(Enum::name).toList();
    }

    protected String createTaskId(String sourceRef, String textContent, List<String> targets, int batchSize) {
        return createTaskId(sourceRef, textContent, targets, batchSize, null);
    }

    protected String createTaskId(String sourceRef,
                                  String textContent,
                                  List<String> targets,
                                  int batchSize,
                                  int defaultMaxRetryCount) {
        return createTaskId(sourceRef, textContent, targets, batchSize, null, defaultMaxRetryCount);
    }

    protected String createTaskId(String sourceRef,
                                  String textContent,
                                  List<String> targets,
                                  int batchSize,
                                  String workloadClass) {
        return createTaskId(sourceRef, textContent, targets, batchSize, workloadClass, (Integer) null);
    }

    protected String createTaskId(String sourceRef,
                                  String textContent,
                                  List<String> targets,
                                  int batchSize,
                                  String workloadClass,
                                  String workerGroupId) {
        return createTaskId(sourceRef, textContent, targets, batchSize, workloadClass, (Integer) null, workerGroupId);
    }

    protected String createTaskId(String sourceRef,
                                  String textContent,
                                  List<String> targets,
                                  int batchSize,
                                  String workloadClass,
                                  Integer defaultMaxRetryCount) {
        return createTaskId(sourceRef, textContent, targets, batchSize, workloadClass, defaultMaxRetryCount, "us");
    }

    protected String createTaskId(String sourceRef,
                                  String textContent,
                                  List<String> targets,
                                  int batchSize,
                                  String workloadClass,
                                  Integer defaultMaxRetryCount,
                                  String workerGroupId) {
        String defaultRoutingCode = "us";
        Map<String, Object> sharedConfig = new LinkedHashMap<>();
        sharedConfig.put("textContent", textContent);
        sharedConfig.put(TaskSharedConfig.ROUTING_CODE, defaultRoutingCode);
        sharedConfig.put(TaskSharedConfig.WORKER_GROUP_ID, workerGroupId);
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "demoApp");
        createBody.put("sharedConfig", sharedConfig);
        createBody.put("userId", "itest");
        createBody.put("sourceRef", sourceRef);
        Map<String, Object> executionSpec = new LinkedHashMap<>();
        executionSpec.put("batchSize", batchSize);
        if (workloadClass != null && !workloadClass.isBlank()) {
            executionSpec.put("workloadClass", workloadClass);
        }
        if (defaultMaxRetryCount != null) {
            executionSpec.put("defaultMaxRetryCount", defaultMaxRetryCount);
        }
        createBody.put("executionSpec", executionSpec);

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertFalse(taskId.isBlank());
        Map<String, Object> ingestResponse = appendTaskItems(
                taskId,
                "demo.dispatch",
                targets.stream()
                        .map(target -> Map.<String, Object>of("target", target))
                        .toList()
        );
        assertApiOk(ingestResponse);
        Map<String, Object> sealResponse = sealTask(taskId);
        assertApiOk(sealResponse);
        return taskId;
    }

    protected String createTaskId(String sourceRef, String textContent, String target) {
        return createTaskId(sourceRef, textContent, List.of(target), 1);
    }

    protected String createTaskId(String sourceRef, String textContent, String target, String workerGroupId) {
        return createTaskId(sourceRef, textContent, List.of(target), 1, null, workerGroupId);
    }

    protected String createTaskId(String sourceRef, String... targets) {
        return createTaskId(sourceRef, sourceRef + " integration", List.of(targets), 1);
    }

    protected Map<String, Object> audit(String taskId, String comment) {
        return approveTask(taskId);
    }

    protected Map<String, Object> createTaskShell(Object body) {
        return exchange("/api/v1/tasks", HttpMethod.POST, body);
    }

    protected Map<String, Object> createTaskShell(Object body, HttpHeaders headers) {
        return exchange("/api/v1/tasks", HttpMethod.POST, body, headers);
    }

    protected Map<String, Object> appendTaskItems(String taskId,
                                                  String eventCode,
                                                  List<?> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (eventCode != null && !eventCode.isBlank()) {
            body.put("eventCode", eventCode);
        }
        body.put("items", items);
        return exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, body);
    }

    protected Map<String, Object> sealTask(String taskId) {
        return executeTaskCommand(taskId, "SEAL");
    }

    protected Map<String, Object> approveTask(String taskId) {
        return executeTaskCommand(taskId, "APPROVE");
    }

    protected Map<String, Object> rejectTask(String taskId) {
        return executeTaskCommand(taskId, "REJECT");
    }

    protected Map<String, Object> pauseTask(String taskId) {
        return executeTaskCommand(taskId, "PAUSE");
    }

    protected Map<String, Object> resumeTask(String taskId) {
        return executeTaskCommand(taskId, "RESUME");
    }

    protected Map<String, Object> blockTask(String taskId) {
        return executeTaskCommand(taskId, "BLOCK");
    }

    protected Map<String, Object> terminateTask(String taskId) {
        return executeTaskCommand(taskId, "TERMINATE");
    }

    protected Map<String, Object> executeTaskCommand(String taskId, String command) {
        return executeTaskCommand(taskId, command, null);
    }

    protected Map<String, Object> executeTaskCommand(String taskId, String command, String reason) {
        return executeTaskCommand(taskId, command, reason, null);
    }

    protected Map<String, Object> executeTaskCommand(String taskId,
                                                     String command,
                                                     String reason,
                                                     HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        if (reason != null && !reason.isBlank()) {
            body.put("reason", reason);
        }
        if (headers == null) {
            return exchange("/api/v1/tasks/" + taskId + "/commands", HttpMethod.POST, body);
        }
        return exchange("/api/v1/tasks/" + taskId + "/commands", HttpMethod.POST, body, headers);
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> responseData(Map<String, Object> response) {
        Object data = response == null ? null : response.get("data");
        if (data instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    protected void assertApiOk(Map<String, Object> response) {
        assertEquals(0, apiCode(response), apiMsg(response));
    }

    protected boolean isApiOk(Map<String, Object> response) {
        return apiCode(response) == 0;
    }

    protected void assertApiError(Map<String, Object> response, int expectedCode) {
        assertEquals(expectedCode, apiCode(response));
    }

    protected int apiCode(Map<String, Object> response) {
        Object code = response == null ? null : response.get("code");
        return code instanceof Number number ? number.intValue() : -1;
    }

    protected String apiMsg(Map<String, Object> response) {
        Object msg = response == null ? null : response.get("msg");
        return msg == null ? null : String.valueOf(msg);
    }

    protected Map<String, Object> waitForTaskDetail(String taskId, String expectedStatus) throws InterruptedException {
        return waitForTaskDetail(taskId, expectedStatus, 20, 250L);
    }

    protected Map<String, Object> waitForTaskDetail(String taskId, String expectedStatus, int maxAttempts, long sleepMillis)
            throws InterruptedException {
        return awaitValue(
                "Task " + taskId + " did not reach " + expectedStatus + " within timeout",
                maxAttempts,
                sleepMillis,
                () -> exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null),
                detailResponse -> expectedStatus.equals(task(detailResponse).get("status")),
                detailResponse -> "status=" + task(detailResponse).get("status")
        );
    }

    protected RuntimeTaskSnapshot waitForRuntimeTaskSnapshot(String taskId,
                                                             String expectedStatus,
                                                             int maxAttempts,
                                                             long sleepMillis) throws InterruptedException {
        return waitForRuntimeTaskSnapshot(
                taskId,
                snapshot -> expectedStatus.equals(snapshot.task().get("status")),
                expectedStatus,
                maxAttempts,
                sleepMillis);
    }

    protected RuntimeTaskSnapshot waitForRuntimeTaskSnapshot(String taskId,
                                                             Predicate<RuntimeTaskSnapshot> condition,
                                                             String expectation,
                                                             int maxAttempts,
                                                             long sleepMillis) throws InterruptedException {
        return awaitValue(
                "Task " + taskId + " did not reach expected runtime state: " + expectation,
                maxAttempts,
                sleepMillis,
                () -> fetchRuntimeTaskSnapshot(taskId),
                condition,
                latestSnapshot -> "status=" + (latestSnapshot == null ? "<none>" : latestSnapshot.task().get("status"))
                        + ", ready=" + (latestSnapshot == null ? 0 : latestSnapshot.stats().readyCount())
                        + ", inflight=" + (latestSnapshot == null ? 0 : latestSnapshot.stats().inflightCount())
        );
    }

    protected RuntimeTaskSnapshot waitForTerminalRuntimeTask(String taskId) throws InterruptedException {
        return waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 250L);
    }

    protected RuntimeTaskSnapshot fetchRuntimeTaskSnapshot(String taskId) {
        Map<String, Object> detailResponse = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> task = task(detailResponse);
        return new RuntimeTaskSnapshot(
                task,
                List.copyOf(resolveTaskWorkRuntime().activeLeases(taskId)),
                resolveTaskWorkRuntime().stats(taskId));
    }

    private TaskWorkRuntime resolveTaskWorkRuntime() {
        return taskWorkRuntime;
    }

    protected void assertClientConnects(SampleWorkerClient client, String failureMessage) throws Exception {
        assertTrue(awaitCondition(() -> {
            try {
                return client.connectBlocking(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, 3, 250L), failureMessage);
    }

    protected SampleWorkerWebSocketClient sampleWebSocketClient(URI serverUri,
                                                                String workerGroupId,
                                                                String workerId) {
        return sampleWebSocketClient(serverUri, workerGroupId, workerId, "SUCCESS");
    }

    protected SampleWorkerWebSocketClient sampleWebSocketClient(URI serverUri,
                                                                String workerGroupId,
                                                                String workerId,
                                                                String taskResultStatus) {
        return new SampleWorkerWebSocketClient(
                withWorkerRouteKey(serverUri, canonicalWorkerRouteKey(workerGroupId, workerId)),
                workerId,
                taskResultStatus
        );
    }

    protected static String canonicalWorkerRouteKey(String workerGroupId, String workerId) {
        return CanonicalWorkerRouteKeyCodec.encode(workerGroupId, workerId);
    }

    public static URI withWorkerRouteKey(URI serverUri, String routeKey) {
        if (serverUri == null) {
            throw new IllegalArgumentException("serverUri must not be null");
        }
        if (routeKey == null || routeKey.isBlank()) {
            throw new IllegalArgumentException("routeKey must not be blank");
        }
        String existingQuery = serverUri.getRawQuery();
        String routeQuery = "routeKey=" + routeKey.trim();
        String mergedQuery = (existingQuery == null || existingQuery.isBlank())
                ? routeQuery
                : existingQuery + "&" + routeQuery;
        try {
            return new URI(
                    serverUri.getScheme(),
                    serverUri.getRawAuthority(),
                    serverUri.getRawPath(),
                    mergedQuery,
                    serverUri.getRawFragment()
            );
        } catch (Exception ex) {
            throw new IllegalArgumentException("Failed to append routeKey to serverUri", ex);
        }
    }

    protected boolean updateStoredTask(Task task) {
        return taskStorage.updateTask(task);
    }

    protected <T extends SampleWorkerClient> T connectClientWithRetries(Supplier<T> clientSupplier,
                                                                         String failureMessage) throws Exception {
        Exception lastError = null;
        for (int i = 0; i < 3; i++) {
            T client = clientSupplier.get();
            try {
                if (client.connectBlocking(3, TimeUnit.SECONDS)) {
                    return client;
                }
                client.disconnect();
            } catch (Exception e) {
                lastError = e;
                try {
                    client.disconnect();
                } catch (Exception ignored) {
                    // Best-effort cleanup for failed connection attempts.
                }
            }
            TimeUnit.MILLISECONDS.sleep(250L);
        }
        if (lastError != null) {
            throw new AssertionError(failureMessage, lastError);
        }
        throw new AssertionError(failureMessage);
    }

    /**
     * Asserts that at least {@code minExpected} workers currently have transport reachability.
     *
     * <p>Call this before dispatching tasks that depend on sample workers being ready.
     * A failure here means SDK resource registration or adapter-specific sample client startup did not
     * converge transport presence - surfacing the problem early rather than waiting for a
     * task to time out in READY state.
     */
    @SuppressWarnings("unchecked")
    protected void assertMinOnlineWorkers(int minExpected) throws InterruptedException {
        int online = awaitValue(
                "Expected at least " + minExpected + " transport-online worker(s)",
                20,
                250L,
                this::fetchOnlineWorkerCount,
                count -> count >= minExpected,
                Object::toString
        );
        if (online < minExpected) {
            throw new AssertionError(
                    "Expected at least " + minExpected + " transport-online worker(s) but found " + online
                            + " after waiting. Check bootstrap config JSON format, transport presence wiring, and sample transport client startup logs.");
        }
    }

    protected WorkerSnapshot waitForWorkerStatus(String workerId,
                                                 String expectedStatus,
                                                 int maxAttempts,
                                                 long sleepMillis,
                                                 Runnable livenessCheck,
                                                 Supplier<String> diagnosticsSupplier) throws InterruptedException {
        try {
            return awaitValue(
                    "Worker " + workerId + " did not reach status " + expectedStatus,
                    maxAttempts,
                    sleepMillis,
                    () -> {
                        if (livenessCheck != null) {
                            livenessCheck.run();
                        }
                        return fetchRuntimeWorker(workerId);
                    },
                    worker -> worker != null
                            && expectedStatus.equals(worker.getStatus()),
                    worker -> worker == null ? "<not-registered>" : worker.toString()
            );
        } catch (AssertionError error) {
            if (livenessCheck != null) {
                livenessCheck.run();
            }
            String diagnostics = diagnosticsSupplier == null ? "" : diagnosticsSupplier.get();
            if (diagnostics == null || diagnostics.isBlank()) {
                throw error;
            }
            throw new AssertionError(error.getMessage() + System.lineSeparator()
                    + "Process output:" + System.lineSeparator() + diagnostics, error);
        }
    }

    protected void waitForWorkerPresenceOnline(String workerId,
                                               int maxAttempts,
                                               long sleepMillis,
                                               Runnable livenessCheck,
                                               Supplier<String> diagnosticsSupplier) throws InterruptedException {
        try {
            boolean online = awaitCondition(() -> {
                if (livenessCheck != null) {
                    livenessCheck.run();
                }
                return requireSdkApp().isWorkerReachable(workerId);
            }, maxAttempts, sleepMillis);
            assertTrue(online, "Worker " + workerId + " did not become transport-online");
        } catch (AssertionError error) {
            if (livenessCheck != null) {
                livenessCheck.run();
            }
            String diagnostics = diagnosticsSupplier == null ? "" : diagnosticsSupplier.get();
            if (diagnostics == null || diagnostics.isBlank()) {
                throw error;
            }
            throw new AssertionError(error.getMessage() + System.lineSeparator()
                    + "Process output:" + System.lineSeparator() + diagnostics, error);
        }
    }

    protected void waitForWorkerOffline(String workerId, String failureMessage) throws InterruptedException {
        assertTrue(awaitCondition(() -> !requireSdkApp().isWorkerReachable(workerId), 20, 100L), failureMessage);
    }

    protected int waitForPositiveIntSystemProperty(String propertyName,
                                                   String failureMessage,
                                                   int maxAttempts,
                                                   long sleepMillis) throws InterruptedException {
        Integer resolvedValue = awaitValue(
                failureMessage,
                maxAttempts,
                sleepMillis,
                () -> parsePositiveInt(System.getProperty(propertyName)),
                value -> value != null,
                value -> value == null ? "<unset>" : value.toString()
        );
        return resolvedValue;
    }

    protected boolean awaitCondition(BooleanSupplier condition, int maxAttempts, long sleepMillis) throws InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            if (i + 1 < maxAttempts) {
                TimeUnit.MILLISECONDS.sleep(sleepMillis);
            }
        }
        return condition.getAsBoolean();
    }

    protected <T> T awaitValue(String failureMessage,
                               int maxAttempts,
                               long sleepMillis,
                               Supplier<T> supplier,
                               Predicate<T> condition,
                               Function<T, String> latestStateRenderer) throws InterruptedException {
        T latestValue = null;
        for (int i = 0; i < maxAttempts; i++) {
            latestValue = supplier.get();
            if (condition.test(latestValue)) {
                return latestValue;
            }
            if (i + 1 < maxAttempts) {
                TimeUnit.MILLISECONDS.sleep(sleepMillis);
            }
        }
        throw new AssertionError(failureMessage + ". Last state="
                + (latestValue == null ? "<none>" : latestStateRenderer.apply(latestValue)));
    }

    private int fetchOnlineWorkerCount() {
        return (int) requireSdkApp().getAllWorkers().stream()
                .map(WorkerSnapshot::getWorkerId)
                .filter(workerId -> workerId != null && requireSdkApp().isWorkerReachable(workerId))
                .count();
    }

    private WorkerSnapshot fetchRuntimeWorker(String workerId) {
        return requireSdkApp().getAllWorkers().stream()
                .filter(worker -> workerId.equals(worker.getWorkerId()))
                .findFirst()
                .orElse(null);
    }

    private Integer parsePositiveInt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    protected void registerSdkWorkerWithContext(String workerId, String routingTag) {
        registerSdkWorkerWithContext(workerId, routingTag, "demoApp");
    }

    protected void registerSdkWorkerWithContext(String workerId, String routingTag, String project) {
        requireSdkApp().registerWorker(createWorkerRegistration(
                workerId,
                "us",
                project,
                1,
                schedulingAttributes(routingTag, Map.of())
        ));
    }

    protected void registerSdkWorkerWithContext(String workerId,
                                                String workerGroupId,
                                                String routingTag,
                                                String project,
                                                Map<String, Object> contextAttributes) {
        requireSdkApp().registerWorker(createWorkerRegistration(
                workerId,
                workerGroupId,
                project,
                1,
                schedulingAttributes(routingTag, contextAttributes)
        ));
    }

    protected void registerSdkStatelessWorker(String workerId, String project) {
        registerSdkStatelessWorker(workerId, project, 1);
    }

    protected void registerSdkStatelessWorker(String workerId, String project, int maxConcurrentWork) {
        requireSdkApp().registerWorker(createWorkerRegistration(workerId, "us", project, maxConcurrentWork));
    }

    protected void registerSdkStatelessWorkerWithAttributes(String workerId,
                                                            String workerGroupId,
                                                            String project,
                                                            Map<String, String> attributes) {
        requireSdkApp().registerWorker(createWorkerRegistration(workerId, workerGroupId, project, 1, attributes));
    }

    private WorkerRegistration createWorkerRegistration(String workerId, String workerGroupId, String project) {
        return createWorkerRegistration(workerId, workerGroupId, project, 1);
    }

    private WorkerRegistration createWorkerRegistration(String workerId,
                                                        String workerGroupId,
                                                        String project,
                                                        int maxConcurrentWork) {
        return createWorkerRegistration(workerId, workerGroupId, project, maxConcurrentWork, Map.of());
    }

    private WorkerRegistration createWorkerRegistration(String workerId,
                                                        String workerGroupId,
                                                        String project,
                                                        int maxConcurrentWork,
                                                        Map<String, String> attributes) {
        String adapterNodeId = "websocket-node";
        requireSdkApp().declareWorkerGroup(WorkerGroupDeclaration.builder()
                .groupId(workerGroupId)
                .eventBindings(defaultEventBindings(project))
                .build());
        requireSdkApp().registerAdapterNode(AdapterNodeRegistration.builder()
                .adapterNodeId(adapterNodeId)
                .adapterType("websocket")
                .endpointId("sample-e2e")
                .build());
        requireSdkApp().bindNodeGroup(NodeGroupBindingRegistration.builder()
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .build());
        return WorkerRegistration.builder()
                .workerId(workerId)
                .adapterNodeId(adapterNodeId)
                .workerGroupId(workerGroupId)
                .adapterId("websocket")
                .transportHint("realtime")
                .maxConcurrentWork(maxConcurrentWork)
                .attributes(attributes == null ? Map.of() : attributes)
                .build();
    }

    private List<WorkerEventBinding> defaultEventBindings(String project) {
        return defaultSupportedEvents(project).stream()
                .map(eventCode -> WorkerEventBinding.builder()
                        .eventCode(eventCode)
                        .projectCodes(List.of(project))
                        .build())
                .toList();
    }

    private List<String> defaultSupportedEvents(String project) {
        List<String> preferred = switch (project) {
            case "crawlerApp" -> List.of("crawler.fetch-page");
            case "testApp" -> List.of("demo.dispatch");
            case "otherApp", "demoApp" -> List.of("demo.dispatch", "demo.dispatch.gb");
            default -> List.of();
        };
        List<String> available = requireSdkApp().getEventsForProject(project).stream()
                .map(EventDefinition::getCode)
                .toList();
        if (available.isEmpty()) {
            throw new IllegalStateException("No runtime events registered for project: " + project);
        }
        List<String> resolved = preferred.stream()
                .filter(available::contains)
                .toList();
        return resolved.isEmpty() ? List.of(available.getFirst()) : resolved;
    }

    private Map<String, String> schedulingAttributes(String routingTag, Map<String, Object> contextAttributes) {
        java.util.LinkedHashMap<String, String> attributes = new java.util.LinkedHashMap<>();
        if (routingTag != null && !routingTag.isBlank()) {
            attributes.put("routingTag", routingTag);
            attributes.put("routingTags", routingTag);
            attributes.put("country", routingTag);
        }
        if (contextAttributes != null) {
            contextAttributes.forEach((key, value) -> {
                if (key != null && value != null) {
                    attributes.put(key, String.valueOf(value));
                }
            });
        }
        return attributes;
    }

    private MassSdkApplication requireSdkApp() {
        if (app == null) {
            throw new IllegalStateException("MassSdkApplication is not available for this E2E fixture");
        }
        return app;
    }

    protected record RuntimeTaskSnapshot(Map<String, Object> task,
                                         List<ActiveLeaseRecord> activeLeases,
                                         TaskWorkStats stats) {
    }
}
