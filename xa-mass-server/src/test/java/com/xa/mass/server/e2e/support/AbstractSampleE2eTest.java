package com.xa.mass.server.e2e.support;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.engine.TaskCompatibilityQueryService;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.workerpack.sample.client.SampleWorkerClient;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.starter.MassApplication;
import com.xa.mass.starter.MassEngine;
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

    @Autowired(required = false)
    protected TaskManager taskManager;

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

    @SuppressWarnings("unchecked")
    protected Map<String, Object> task(Map<String, Object> response) {
        return (Map<String, Object>) responseData(response).get("task");
    }

    @SuppressWarnings("unchecked")
    protected List<Map<String, Object>> messages(Map<String, Object> response) {
        return (List<Map<String, Object>>) responseData(response).get("messages");
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> stateValidation(Map<String, Object> response) {
        return (Map<String, Object>) responseData(response).get("stateValidation");
    }

    @SuppressWarnings("unchecked")
    protected List<String> violations(Map<String, Object> validation) {
        return (List<String>) validation.get("violations");
    }

    protected String createTaskId(String taskName, String textContent, List<String> targets, int batchSize) {
        return createTaskId(taskName, textContent, targets, batchSize, null);
    }

    protected String createTaskId(String taskName,
                                  String textContent,
                                  List<String> targets,
                                  int batchSize,
                                  TaskWorkloadClass workloadClass) {
        String defaultRoutingCode = "us";
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("taskName", taskName);
        createBody.put("project", "demoApp");
        createBody.put("payloadType", "JSON");
        createBody.put("sharedConfig", java.util.Map.of("textContent", textContent, "routingCode", defaultRoutingCode));
        createBody.put("userId", "itest");
        if (workloadClass != null) {
            createBody.put("workloadClass", workloadClass.name());
        }
        createBody.put("batchSize", batchSize);

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertFalse(taskId.isBlank());
        Map<String, Object> ingestResponse = appendTaskItems(
                taskId,
                targets.stream()
                        .map(target -> Map.<String, Object>of("target", target))
                        .toList(),
                3
        );
        assertApiOk(ingestResponse);
        Map<String, Object> sealResponse = sealTask(taskId);
        assertApiOk(sealResponse);
        return taskId;
    }

    protected String createTaskId(String taskName, String textContent, String target) {
        return createTaskId(taskName, textContent, List.of(target), 1);
    }

    protected String createTaskId(String taskName, String... targets) {
        return createTaskId(taskName, taskName + " integration", List.of(targets), 1);
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

    protected Map<String, Object> appendTaskItems(String taskId, List<?> items, int defaultMsgMaxRetryCount) {
        return exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                "items", items,
                "defaultMsgMaxRetryCount", defaultMsgMaxRetryCount
        ));
    }

    protected Map<String, Object> sealTask(String taskId) {
        return exchange("/api/v1/tasks/" + taskId + ":seal", HttpMethod.POST, null);
    }

    protected Map<String, Object> approveTask(String taskId) {
        return exchange("/api/v1/tasks/" + taskId + ":approve", HttpMethod.POST, null);
    }

    protected Map<String, Object> rejectTask(String taskId) {
        return exchange("/api/v1/tasks/" + taskId + ":reject", HttpMethod.POST, null);
    }

    protected Map<String, Object> pauseTask(String taskId) {
        return exchange("/api/v1/tasks/" + taskId + ":pause", HttpMethod.POST, null);
    }

    protected Map<String, Object> resumeTask(String taskId) {
        return exchange("/api/v1/tasks/" + taskId + ":resume", HttpMethod.POST, null);
    }

    protected Map<String, Object> blockTask(String taskId) {
        return exchange("/api/v1/tasks/" + taskId + ":block", HttpMethod.POST, null);
    }

    protected Map<String, Object> terminateTask(String taskId) {
        return exchange("/api/v1/tasks/" + taskId + ":terminate", HttpMethod.POST, null);
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

    protected TaskSnapshot waitForTaskSnapshot(String taskId, String expectedStatus) throws InterruptedException {
        return waitForTaskSnapshot(taskId, expectedStatus, 20, 250L);
    }

    protected TaskSnapshot waitForTaskSnapshot(String taskId, String expectedStatus, int maxAttempts, long sleepMillis)
            throws InterruptedException {
        return waitForTaskSnapshot(taskId,
                snapshot -> expectedStatus.equals(snapshot.task().get("status")),
                expectedStatus,
                maxAttempts,
                sleepMillis);
    }

    protected TaskSnapshot waitForTaskSnapshot(String taskId,
                                               Predicate<TaskSnapshot> condition,
                                               String expectation,
                                               int maxAttempts,
                                               long sleepMillis) throws InterruptedException {
        return awaitValue(
                "Task " + taskId + " did not reach expected snapshot: " + expectation,
                maxAttempts,
                sleepMillis,
                () -> fetchTaskSnapshot(taskId),
                condition,
                latestSnapshot -> "status=" + (latestSnapshot == null ? "<none>" : latestSnapshot.task().get("status"))
                        + ", messages=" + (latestSnapshot == null ? 0 : latestSnapshot.messages().size())
        );
    }

    protected TaskSnapshot waitForTerminalTask(String taskId) throws InterruptedException {
        return waitForTaskSnapshot(taskId, "TERMINAL");
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

    protected TaskSnapshot fetchTaskSnapshot(String taskId) {
        Map<String, Object> detailResponse = exchange("/api/v1/tasks/" + taskId, HttpMethod.GET, null);
        return new TaskSnapshot(task(detailResponse), fetchCompatibilityMessages(taskId, 500));
    }

    protected List<Map<String, Object>> fetchTaskMessageAttempts(String taskId, String messageId) {
        List<Map<String, Object>> attempts = new java.util.ArrayList<>();
        resolveTaskCompatibilityQueries().visitTaskMessageAttemptViews(taskId, messageId,
                (attemptId, ignoredTaskId, ignoredMessageId, attemptNo, workerId, workerContextId, batchId, status,
                 leaseExpireTime, dispatchTime, ackTime, startTime, finishTime, finalReason, errorMessage, errorCode,
                 output, createTime, updateTime) -> {
                    Map<String, Object> attempt = new LinkedHashMap<>();
                    attempt.put("attemptId", attemptId);
                    attempt.put("taskId", ignoredTaskId);
                    attempt.put("messageId", ignoredMessageId);
                    attempt.put("attemptNo", attemptNo);
                    attempt.put("workerId", workerId);
                    attempt.put("workerContextId", workerContextId);
                    attempt.put("batchId", batchId);
                    attempt.put("status", status);
                    attempt.put("leaseExpireTime", leaseExpireTime);
                    attempt.put("dispatchTime", dispatchTime);
                    attempt.put("ackTime", ackTime);
                    attempt.put("startTime", startTime);
                    attempt.put("finishTime", finishTime);
                    attempt.put("finalReason", finalReason);
                    attempt.put("errorMessage", errorMessage);
                    attempt.put("errorCode", errorCode);
                    attempt.put("output", output == null ? null : new LinkedHashMap<>(output));
                    attempt.put("createTime", createTime);
                    attempt.put("updateTime", updateTime);
                    attempts.add(attempt);
                });
        return attempts;
    }

    private List<Map<String, Object>> fetchCompatibilityMessages(String taskId, int limit) {
        List<Map<String, Object>> messages = new java.util.ArrayList<>();
        resolveTaskCompatibilityQueries().visitTaskMessageSnapshot(taskId, limit,
                (messageId, ignoredTaskId, status, latestAttemptId, latestAttemptWorkerId, latestAttemptWorkerContextId,
                 latestAttemptBatchId, retryCount, maxRetryCount, errorMessage, errorCode, finalReason, payloadRef,
                 input, output, assignedTime, createTime, updateTime, startTime, completeTime) -> {
                    Map<String, Object> message = new LinkedHashMap<>();
                    message.put("messageId", messageId);
                    message.put("taskId", ignoredTaskId);
                    message.put("status", status);
                    message.put("latestAttemptId", latestAttemptId);
                    message.put("latestAttemptWorkerId", latestAttemptWorkerId);
                    message.put("latestAttemptWorkerContextId", latestAttemptWorkerContextId);
                    message.put("latestAttemptBatchId", latestAttemptBatchId);
                    message.put("retryCount", retryCount);
                    message.put("maxRetryCount", maxRetryCount);
                    message.put("errorMessage", errorMessage);
                    message.put("errorCode", errorCode);
                    message.put("finalReason", finalReason);
                    message.put("payloadRef", payloadRef);
                    message.put("input", input == null ? null : new LinkedHashMap<>(input));
                    message.put("output", output == null ? null : new LinkedHashMap<>(output));
                    message.put("result", output == null ? null : new LinkedHashMap<>(output));
                    message.put("assignedTime", assignedTime);
                    message.put("createTime", createTime);
                    message.put("updateTime", updateTime);
                    message.put("startTime", startTime);
                    message.put("completeTime", completeTime);
                    messages.add(message);
                });
        return messages;
    }

    private TaskCompatibilityQueryService resolveTaskCompatibilityQueries() {
        if (taskManager != null) {
            return new TaskCompatibilityQueryService(taskManager);
        }
        if (app == null) {
            throw new IllegalStateException("TaskManager test dependency is not available");
        }
        try {
            java.lang.reflect.Field delegateField = MassSdkApplication.class.getDeclaredField("delegate");
            delegateField.setAccessible(true);
            MassApplication delegate = (MassApplication) delegateField.get(app);
            MassEngine engine = delegate.getEngine();
            if (engine == null) {
                throw new IllegalStateException("Mass engine is unavailable for test compatibility queries");
            }
            java.lang.reflect.Field configField = MassEngine.class.getDeclaredField("config");
            configField.setAccessible(true);
            Object config = configField.get(engine);
            java.lang.reflect.Method taskQueryMethod = config.getClass().getMethod("getTaskQueryService");
            taskQueryMethod.invoke(config);
            java.lang.reflect.Field taskManagerField = config.getClass().getDeclaredField("taskManager");
            taskManagerField.setAccessible(true);
            TaskManager reflectedTaskManager = (TaskManager) taskManagerField.get(config);
            if (reflectedTaskManager == null) {
                throw new IllegalStateException("Task manager is unavailable for test compatibility queries");
            }
            return new TaskCompatibilityQueryService(reflectedTaskManager);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Task compatibility queries are unavailable for tests", e);
        }
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

    protected boolean updateStoredTask(Task task) {
        return requireDelegate().getEngine().getConfig().getTaskCommandService().updateTask(task);
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
     * Asserts that at least {@code minExpected} ONLINE workers are registered with the runtime.
     *
     * <p>Call this before dispatching tasks that depend on sample realtime workers being ready.
     * A failure here means SDK resource registration or adapter-specific sample client startup did not
     * produce the expected workers - surfacing the problem early rather than waiting for a
     * task to time out in READY state.
     */
    @SuppressWarnings("unchecked")
    protected void assertMinOnlineWorkers(int minExpected) throws InterruptedException {
        int online = awaitValue(
                "Expected at least " + minExpected + " ONLINE worker(s)",
                20,
                250L,
                this::fetchOnlineWorkerCount,
                count -> count >= minExpected,
                Object::toString
        );
        if (online < minExpected) {
            throw new AssertionError(
                    "Expected at least " + minExpected + " ONLINE worker(s) but found " + online
                            + " after waiting. Check bootstrap config JSON format and sample transport client startup logs.");
        }
    }

    protected Worker waitForWorkerStatus(String workerId,
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
                            && worker.getStatus() != null
                            && expectedStatus.equals(worker.getStatus().name()),
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

    protected void waitForWorkerOffline(String workerId, String failureMessage) throws InterruptedException {
        assertTrue(awaitCondition(() -> !requireSdkApp().isWorkerOnline(workerId), 20, 100L), failureMessage);
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

    @SuppressWarnings("unchecked")
    private int fetchOnlineWorkerCount() {
        Map<String, Object> response = exchange("/api/v1/meta/worker-capabilities", HttpMethod.GET, null);
        if (!isApiOk(response)) {
            return 0;
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> list)) {
            return 0;
        }
        return (int) list.stream()
                .filter(item -> item instanceof Map<?, ?> m && "ONLINE".equals(m.get("status")))
                .count();
    }

    private Worker fetchRuntimeWorker(String workerId) {
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
        requireSdkApp().registerWorker(createWorkerRegistration(workerId, "us", project));
        requireSdkApp().registerWorkerContext(createWorkerContextRegistration(workerId, routingTag));
    }

    protected void registerSdkWorkerWithContext(String workerId,
                                                String workerGroupId,
                                                String routingTag,
                                                String project,
                                                Map<String, Object> contextAttributes) {
        requireSdkApp().registerWorker(createWorkerRegistration(workerId, workerGroupId, project));
        requireSdkApp().registerWorkerContext(createWorkerContextRegistration(workerId, routingTag, contextAttributes));
    }

    protected void registerSdkStatelessWorker(String workerId, String project) {
        requireSdkApp().registerWorker(createWorkerRegistration(workerId, "us", project));
    }

    private MassApplication requireDelegate() {
        assertNotNull(app, "MassSdkApplication is required");
        return readField(app, "delegate", MassApplication.class);
    }

    private static <T> T readField(Object target, String fieldName, Class<T> fieldType) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return fieldType.cast(field.get(target));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private WorkerRegistration createWorkerRegistration(String workerId, String workerGroupId, String project) {
        return WorkerRegistration.builder()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .eventBindings(defaultEventBindings(project))
                .adapterId("websocket")
                .transportHint("realtime")
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
        return switch (project) {
            case "crawlerApp" -> List.of("crawler.fetch-page");
            case "testApp" -> List.of("demo.dispatch");
            case "otherApp", "demoApp" -> List.of("demo.dispatch", "demo.dispatch.gb");
            default -> List.of();
        };
    }

    private WorkerContextRegistration createWorkerContextRegistration(String workerId, String routingTag) {
        return createWorkerContextRegistration(workerId, routingTag, Map.of());
    }

    private WorkerContextRegistration createWorkerContextRegistration(String workerId,
                                                                     String routingTag,
                                                                     Map<String, Object> contextAttributes) {
        Map<String, String> normalizedAttributes = contextAttributes == null || contextAttributes.isEmpty()
                ? Map.of()
                : contextAttributes.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.valueOf(entry.getValue()),
                        (left, right) -> right,
                        java.util.LinkedHashMap::new
                ));
        return WorkerContextRegistration.builder()
                .workerContextId("worker-context-" + workerId)
                .workerId(workerId)
                .routingTags(java.util.Set.of(routingTag))
                .attributes(normalizedAttributes)
                .build();
    }

    private MassSdkApplication requireSdkApp() {
        if (app == null) {
            throw new IllegalStateException("MassSdkApplication is not available for this E2E fixture");
        }
        return app;
    }

    protected record TaskSnapshot(Map<String, Object> task, List<Map<String, Object>> messages) {
    }
}
