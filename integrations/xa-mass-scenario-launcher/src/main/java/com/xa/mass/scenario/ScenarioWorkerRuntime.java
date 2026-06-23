package com.xa.mass.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import com.xa.mass.client.worker.WorkerAction;
import com.xa.mass.client.worker.WorkerRuntimeDefinition;
import com.xa.mass.client.worker.runtime.PollingWorkerRuntime;
import com.xa.mass.client.worker.handler.WorkerActionResult;
import com.xa.mass.client.worker.runtime.WebSocketWorkerRuntime;
import com.xa.mass.client.worker.runtime.WorkerRuntime;
import com.xa.mass.client.worker.runtime.WorkerRuntimeFailureEvent;
import com.xa.mass.client.worker.runtime.WorkerRuntimeListener;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class ScenarioWorkerRuntime implements AutoCloseable {
    private static final ObjectMapper RESULT_MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<Map<String, Object>> BODY_MAP_TYPE = new TypeReference<>() {
    };
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250L);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500L);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10L);

    private final ScenarioLauncherOptions options;
    private final ScenarioClientFactory clientFactory;
    private final ScenarioIdleTracker idleTracker;
    private final List<WorkerRuntime> sessions = new ArrayList<>();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private final AtomicBoolean closing = new AtomicBoolean(false);

    ScenarioWorkerRuntime(ScenarioLauncherOptions options,
                          ScenarioClientFactory clientFactory,
                          ScenarioIdleTracker idleTracker) {
        this.options = Objects.requireNonNull(options, "options is required");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
        this.idleTracker = Objects.requireNonNull(idleTracker, "idleTracker is required");
    }

    int start(List<WorkerScenarioSpec> workerSpecs) {
        List<WorkerScenarioSpec> launchable = launchablePollingSpecs(workerSpecs, options.maxPollingWorkers());
        for (WorkerScenarioSpec spec : launchable) {
            sessions.add(startPollingSession(spec));
        }
        for (WorkerScenarioSpec spec : launchableWebSocketSpecs(workerSpecs, options.webSocketUrl() != null)) {
            sessions.add(startWebSocketSession(spec));
        }
        if (!sessions.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "java-scenario-launcher-shutdown"));
        }
        return sessions.size();
    }

    void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        for (WorkerRuntime session : sessions) {
            try {
                session.close();
            } catch (Exception e) {
                System.err.printf("[java-scenario-launcher] failed to close worker session: %s%n", e.getMessage());
            }
        }
        shutdownLatch.countDown();
    }

    static List<WorkerScenarioSpec> launchablePollingSpecs(List<WorkerScenarioSpec> specs, int maxWorkers) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        int limit = maxWorkers == 0 ? Integer.MAX_VALUE : maxWorkers;
        List<WorkerScenarioSpec> result = new ArrayList<>();
        for (WorkerScenarioSpec spec : specs) {
            if (isPollingLaunchSpec(spec)) {
                result.add(spec);
                if (result.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(result);
    }

    static List<WorkerScenarioSpec> launchableWebSocketSpecs(List<WorkerScenarioSpec> specs, boolean hasWebSocketUrl) {
        if (!hasWebSocketUrl || specs == null || specs.isEmpty()) {
            return List.of();
        }
        return specs.stream()
                .filter(ScenarioWorkerRuntime::isWebSocketLaunchSpec)
                .toList();
    }

    private WorkerRuntime startPollingSession(WorkerScenarioSpec spec) {
        String workerId = requireNonBlank(spec.workerId(), "workerId");
        String workerGroupId = requireNonBlank(spec.workerGroupId(), "workerGroupId");
        MassPlatform client = clientFactory.forApiKey(workerApiKey(spec));
        PollingWorkerRuntime.Builder builder = client.workerRuntimes().polling(runtimeDefinition(spec, workerId, workerGroupId))
                .pollInterval(POLL_INTERVAL)
                .pollTimeout(POLL_TIMEOUT)
                .heartbeatInterval(HEARTBEAT_INTERVAL)
                .maxMessages(10)
                .listener(new LoggingWorkerRuntimeListener());
        PollingWorkerRuntime session = builder.start();
        System.out.printf("[java-scenario-launcher] started polling worker session %s%n", workerId);
        idleTracker.markActivity();
        return session;
    }

    private WorkerRuntime startWebSocketSession(WorkerScenarioSpec spec) {
        String workerId = requireNonBlank(spec.workerId(), "workerId");
        String workerGroupId = requireNonBlank(spec.workerGroupId(), "workerGroupId");
        MassPlatform client = clientFactory.forApiKey(workerApiKey(spec));
        WebSocketWorkerRuntime.Builder builder = client.workerRuntimes().webSocket(runtimeDefinition(spec, workerId, workerGroupId))
                .endpoint(options.webSocketUrl())
                .listener(new LoggingWorkerRuntimeListener());
        WebSocketWorkerRuntime session = builder.start();
        System.out.printf("[java-scenario-launcher] started websocket worker session %s%n", workerId);
        idleTracker.markActivity();
        return session;
    }

    private WorkerRuntimeDefinition runtimeDefinition(WorkerScenarioSpec spec, String workerId, String workerGroupId) {
        WorkerRuntimeDefinition.Builder builder = WorkerRuntimeDefinition.builder()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .attributes(spec.attributes());
        for (WorkerEventBindingSpec binding : spec.eventBindings() == null ? List.<WorkerEventBindingSpec>of() : spec.eventBindings()) {
            if (binding.eventCode() != null && !binding.eventCode().isBlank()) {
                builder.event(binding.eventCode(), dispatch -> handleDispatch(spec, dispatch));
            }
        }
        return builder.build();
    }

    private WorkerActionResult handleDispatch(WorkerScenarioSpec spec, WorkerAction dispatch) {
        idleTracker.markActivity();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workerId", spec.workerId());
        output.put("eventCode", dispatch.eventCode());
        output.put("handledAt", Instant.now().toString());
        output.put("integrationProbe", integrationProbe(spec));
        output.put("workerProfile", Map.of(
                "runtime", "java-scenario-launcher",
                "language", "java",
                "transport", isWebSocketLaunchSpec(spec) ? "websocket" : "polling",
                "workerId", spec.workerId(),
                "workerGroupId", spec.workerGroupId()
        ));
        output.put("body", actionBody(dispatch));
        output.put("sharedConfig", dispatch.sharedConfig().asMap());
        output.put("detail", "java-scenario-launcher-success");
        return WorkerActionResult.success(resultBody(output));
    }

    private static Map<String, Object> actionBody(WorkerAction action) {
        String body = action.body();
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        try {
            return RESULT_MAPPER.readValue(body, BODY_MAP_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of("rawBody", body);
        }
    }

    private static String resultBody(Map<String, Object> output) {
        try {
            return RESULT_MAPPER.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to encode worker result", e);
        }
    }

    private String workerApiKey(WorkerScenarioSpec spec) {
        if (options.workerApiKey() != null && !options.workerApiKey().isBlank()) {
            return options.workerApiKey();
        }
        return requireNonBlank(spec.workerKey(), "workerKey");
    }

    private static boolean isPollingLaunchSpec(WorkerScenarioSpec spec) {
        return "api-online".equals(spec.startMode())
                || "polling".equals(spec.transportHint())
                || "polling".equals(spec.adapterType());
    }

    private static boolean isWebSocketLaunchSpec(WorkerScenarioSpec spec) {
        boolean adapterIsWebSocket = "websocket".equals(spec.adapterType());
        return "websocket".equals(spec.startMode())
                || adapterIsWebSocket
                || ("realtime".equals(spec.transportHint())
                && (spec.adapterType() == null || spec.adapterType().isBlank() || adapterIsWebSocket));
    }

    private static String integrationProbe(WorkerScenarioSpec spec) {
        return isWebSocketLaunchSpec(spec)
                ? "java-scenario-launcher-websocket"
                : "java-scenario-launcher-polling";
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static final class LoggingWorkerRuntimeListener implements WorkerRuntimeListener {
        @Override
        public void onFailure(WorkerRuntimeFailureEvent failure) {
            System.err.printf("[java-scenario-launcher] worker runtime failure workerId=%s kind=%s reason=%s consecutiveFailures=%s replyRef=%s error=%s%n",
                    failure.workerId(),
                    failure.kind(),
                    failure.reason(),
                    failure.consecutiveFailures(),
                    failure.replyRef(),
                    failure.errorMessage());
        }
    }
}
