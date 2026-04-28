package com.xa.mass.server.e2e.support;

import com.xa.mass.base.enums.task.TaskWorkloadClass;
import com.xa.mass.base.model.Task;
import com.xa.mass.workerpack.sample.client.SampleWorkerClient;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.WorkerContextRegistration;
import com.xa.mass.sdk.model.WorkerEventBinding;
import com.xa.mass.sdk.model.WorkerRegistration;
import com.xa.mass.starter.MassApplication;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

public abstract class AbstractMockE2eTest {

    private static final AtomicInteger NEXT_WEBSOCKET_PORT = new AtomicInteger(initialPortSeed());

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired(required = false)
    protected MassSdkApplication app;

    protected static void registerWebSocketProperties(DynamicPropertyRegistry registry, int websocketPort) {
        registry.add("mass.websocket.port", () -> websocketPort);
    }

    protected static void registerWebSocketPropertiesWithClientUri(DynamicPropertyRegistry registry, int websocketPort) {
        registerWebSocketProperties(registry, websocketPort);
        registry.add("sample.client.websocket-uri", () -> "ws://127.0.0.1:" + websocketPort + "/ws");
    }

    protected static int findFreePort() {
        while (true) {
            int candidate = NEXT_WEBSOCKET_PORT.getAndIncrement();
            try (ServerSocket socket = new ServerSocket(candidate)) {
                return socket.getLocalPort();
            } catch (IOException ignored) {
                // Try the next candidate. Tests run in one JVM, so monotonic allocation
                // avoids reusing a just-released WebSocket port in later contexts.
            }
        }
    }

    private static int initialPortSeed() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to allocate initial free port seed", e);
        }
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
        createBody.put("sharedConfig", java.util.Map.of("textContent", textContent, "routingCode", defaultRoutingCode));
        createBody.put("userId", "itest");
        if (workloadClass != null) {
            createBody.put("workloadClass", workloadClass.name());
        }
        createBody.put("inputs", targets.stream()
                .map(target -> Map.<String, Object>of("target", target))
                .toList());
        createBody.put("batchSize", batchSize);

        Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertFalse(taskId.isBlank());
        return taskId;
    }

    protected String createTaskId(String taskName, String textContent, String target) {
        return createTaskId(taskName, textContent, List.of(target), 1);
    }

    protected String createTaskId(String taskName, String... targets) {
        return createTaskId(taskName, taskName + " integration", List.of(targets), 1);
    }

    protected Map<String, Object> audit(String taskId, String comment) {
        return exchange("/status/api/tasks/" + taskId + "/audit?approved=true&comment=" + comment, HttpMethod.POST, null);
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
        TaskSnapshot latestSnapshot = null;
        for (int i = 0; i < maxAttempts; i++) {
            latestSnapshot = fetchTaskSnapshot(taskId);
            if (condition.test(latestSnapshot)) {
                return latestSnapshot;
            }
            Thread.sleep(sleepMillis);
        }
        throw new AssertionError("Task " + taskId + " did not reach expected snapshot: " + expectation
                + ". Last status=" + (latestSnapshot == null ? "<none>" : latestSnapshot.task().get("status"))
                + ", messages=" + (latestSnapshot == null ? 0 : latestSnapshot.messages().size()));
    }

    protected TaskSnapshot waitForTerminalTask(String taskId) throws InterruptedException {
        return waitForTaskSnapshot(taskId, "TERMINAL");
    }

    protected Map<String, Object> waitForTaskDetail(String taskId, String expectedStatus) throws InterruptedException {
        return waitForTaskDetail(taskId, expectedStatus, 20, 250L);
    }

    protected Map<String, Object> waitForTaskDetail(String taskId, String expectedStatus, int maxAttempts, long sleepMillis)
            throws InterruptedException {
        for (int i = 0; i < maxAttempts; i++) {
            Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
            if (expectedStatus.equals(task(detailResponse).get("status"))) {
                return detailResponse;
            }
            Thread.sleep(sleepMillis);
        }
        throw new AssertionError("Task " + taskId + " did not reach " + expectedStatus + " within timeout");
    }

    protected TaskSnapshot fetchTaskSnapshot(String taskId) {
        Map<String, Object> detailResponse = exchange("/status/api/tasks/" + taskId, HttpMethod.GET, null);
        Map<String, Object> messagesResponse = exchange(
                "/status/api/tasks/" + taskId + "/messages",
                HttpMethod.GET,
                null
        );
        return new TaskSnapshot(task(detailResponse), messages(messagesResponse));
    }

    protected void assertClientConnects(SampleWorkerClient client, String failureMessage) throws Exception {
        boolean connected = false;
        for (int i = 0; i < 3; i++) {
            if (client.connectBlocking(3, TimeUnit.SECONDS)) {
                connected = true;
                break;
            }
            Thread.sleep(250L);
        }
        assertTrue(connected, failureMessage);
    }

    protected boolean updateStoredTask(Task task) {
        return requireDelegate().getEngine().getConfig().getTaskManager().updateTask(task);
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
            Thread.sleep(250L);
        }
        if (lastError != null) {
            throw new AssertionError(failureMessage, lastError);
        }
        throw new AssertionError(failureMessage);
    }

    /**
     * Asserts that at least {@code minExpected} ONLINE workers are registered with the runtime.
     *
     * <p>Call this before dispatching tasks that depend on mock realtime workers being ready.
     * A failure here means SDK resource registration or adapter-specific mock client startup did not
     * produce the expected workers - surfacing the problem early rather than waiting for a
     * task to time out in READY state.
     */
    @SuppressWarnings("unchecked")
    protected void assertMinOnlineWorkers(int minExpected) throws InterruptedException {
        int online = 0;
        for (int i = 0; i < 20; i++) {
            Map<String, Object> response = exchange("/status/api/workers", HttpMethod.GET, null);
            if (isApiOk(response)) {
                Object data = response.get("data");
                if (data instanceof Map<?, ?> dataMap) {
                    Object items = dataMap.get("items");
                    if (items instanceof List<?> list) {
                        online = (int) list.stream()
                                .filter(item -> item instanceof Map<?, ?> m && "ONLINE".equals(m.get("status")))
                                .count();
                        if (online >= minExpected) return;
                    }
                }
            }
            Thread.sleep(250L);
        }
        throw new AssertionError(
                "Expected at least " + minExpected + " ONLINE worker(s) but found " + online
                + " after waiting. Check bootstrap config JSON format and mock transport client startup logs.");
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

