package com.xa.mass.scenario;

import com.xa.mass.client.MassPlatform;
import com.xa.mass.client.task.TaskGetResult;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import com.xa.mass.client.worker.session.DispatchContext;
import com.xa.mass.client.worker.session.PollingWorkerSession;
import com.xa.mass.client.worker.session.WorkerResult;
import com.xa.mass.client.worker.session.WorkerSessionDispatchFailure;
import com.xa.mass.client.worker.session.WorkerSessionListener;
import com.xa.mass.client.worker.session.WorkerSessionPollFailure;
import com.xa.mass.client.worker.session.WorkerSessionStartupFailure;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class ScenarioWorkerRuntime implements AutoCloseable {
    private static final Duration POLL_INTERVAL = Duration.ofMillis(250L);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(500L);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10L);

    private final ScenarioLauncherOptions options;
    private final ScenarioClientFactory clientFactory;
    private final ScenarioIdleTracker idleTracker;
    private final List<PollingWorkerSession> sessions = new ArrayList<>();
    private final List<String> startedWorkerGroupIds = new ArrayList<>();
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
            if (spec.workerGroupId() != null && !spec.workerGroupId().isBlank()
                    && !startedWorkerGroupIds.contains(spec.workerGroupId())) {
                startedWorkerGroupIds.add(spec.workerGroupId());
            }
        }
        if (!sessions.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::close, "java-scenario-launcher-shutdown"));
        }
        return sessions.size();
    }

    List<String> startedWorkerGroupIds() {
        return List.copyOf(startedWorkerGroupIds);
    }

    void awaitShutdownOrIdle(List<TaskScenarioSeeder.SeededTask> seededTasks) throws InterruptedException {
        List<TaskScenarioSeeder.SeededTask> managedTasks = managedTasks(seededTasks);
        if (options.idleTimeoutMs() == 0) {
            shutdownLatch.await();
            return;
        }
        while (!closing.get()) {
            if (idleTracker.isIdleFor(options.idleTimeoutMs()) && allManagedTasksTerminal(managedTasks)) {
                System.out.printf("[java-scenario-launcher] idle timeout reached idleMs=%d%n",
                        idleTracker.idleMillis());
                return;
            }
            if (shutdownLatch.await(500L, TimeUnit.MILLISECONDS)) {
                return;
            }
        }
    }

    private List<TaskScenarioSeeder.SeededTask> managedTasks(List<TaskScenarioSeeder.SeededTask> seededTasks) {
        if (seededTasks == null || seededTasks.isEmpty()) {
            return List.of();
        }
        return seededTasks.stream()
                .filter(TaskScenarioSeeder.SeededTask::managedByLauncherWorkers)
                .toList();
    }

    private boolean allManagedTasksTerminal(List<TaskScenarioSeeder.SeededTask> managedTasks) {
        if (managedTasks.isEmpty()) {
            return true;
        }
        for (TaskScenarioSeeder.SeededTask task : managedTasks) {
            try {
                TaskGetResult result = clientFactory.forApiKey(task.taskApiKey()).tasks().get(task.taskId());
                String status = result == null || result.task() == null ? null : result.task().status();
                if (!"TERMINAL".equalsIgnoreCase(status)) {
                    System.out.printf("[java-scenario-launcher] idle observed but managed task still %s taskId=%s%n",
                            status == null ? "UNKNOWN" : status, task.taskId());
                    idleTracker.markActivity();
                    return false;
                }
            } catch (RuntimeException e) {
                System.err.printf("[java-scenario-launcher] failed to check task status taskId=%s error=%s%n",
                        task.taskId(), e.getMessage());
                idleTracker.markActivity();
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        for (PollingWorkerSession session : sessions) {
            try {
                session.close();
            } catch (RuntimeException e) {
                System.err.printf("[java-scenario-launcher] failed to close polling session: %s%n", e.getMessage());
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

    private PollingWorkerSession startPollingSession(WorkerScenarioSpec spec) {
        String workerId = requireNonBlank(spec.workerId(), "workerId");
        String workerGroupId = requireNonBlank(spec.workerGroupId(), "workerGroupId");
        String adapterNodeId = WorkerScenarioRegistrar.adapterNodeIdFor(spec);
        MassPlatform client = clientFactory.forApiKey(workerApiKey(spec));
        PollingWorkerSession.Builder builder = client.workerSessions().polling()
                .workerId(workerId)
                .workerGroupId(workerGroupId)
                .adapterNodeId(adapterNodeId)
                .adapterType("polling")
                .endpointId(adapterNodeId)
                .attributes(spec.attributes())
                .pollInterval(POLL_INTERVAL)
                .pollTimeout(POLL_TIMEOUT)
                .heartbeatInterval(HEARTBEAT_INTERVAL)
                .maxMessages(10)
                .listener(new LoggingWorkerSessionListener());
        for (WorkerEventBindingSpec binding : spec.eventBindings() == null ? List.<WorkerEventBindingSpec>of() : spec.eventBindings()) {
            if (binding.eventCode() != null && !binding.eventCode().isBlank()) {
                builder.event(binding.eventCode(), dispatch -> handleDispatch(spec, dispatch));
            }
        }
        PollingWorkerSession session = builder.start();
        System.out.printf("[java-scenario-launcher] started polling worker session %s%n", workerId);
        idleTracker.markActivity();
        return session;
    }

    private WorkerResult handleDispatch(WorkerScenarioSpec spec, DispatchContext dispatch) {
        idleTracker.markActivity();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("workerId", spec.workerId());
        output.put("eventCode", dispatch.eventCode());
        output.put("handledAt", Instant.now().toString());
        output.put("integrationProbe", "java-scenario-launcher-polling");
        output.put("workerProfile", Map.of(
                "runtime", "java-scenario-launcher",
                "language", "java",
                "workerId", spec.workerId(),
                "workerGroupId", spec.workerGroupId()
        ));
        output.put("input", dispatch.input().asMap());
        output.put("sharedConfig", dispatch.sharedConfig().asMap());
        return WorkerResult.success("java-scenario-launcher-success", output);
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
                || "polling".equals(spec.adapterId());
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value;
    }

    private static final class LoggingWorkerSessionListener implements WorkerSessionListener {
        @Override
        public void onStartupFailure(WorkerSessionStartupFailure failure) {
            System.err.printf("[java-scenario-launcher] worker startup failed workerId=%s step=%s error=%s%n",
                    failure.workerId(), failure.failedStep(), failure.cause().getMessage());
        }

        @Override
        public void onHandlerFailure(WorkerSessionDispatchFailure failure) {
            System.err.printf("[java-scenario-launcher] worker handler failed workerId=%s taskId=%s messageId=%s error=%s%n",
                    failure.dispatch().workerId(), failure.dispatch().taskId(),
                    failure.dispatch().messageId(), failure.cause().getMessage());
        }

        @Override
        public void onSubmitFailure(WorkerSessionDispatchFailure failure) {
            System.err.printf("[java-scenario-launcher] worker submit failed workerId=%s taskId=%s messageId=%s error=%s%n",
                    failure.dispatch().workerId(), failure.dispatch().taskId(),
                    failure.dispatch().messageId(), failure.cause().getMessage());
        }

        @Override
        public void onPollFailure(WorkerSessionPollFailure failure) {
            System.err.printf("[java-scenario-launcher] worker poll failed workerId=%s consecutiveFailures=%d error=%s%n",
                    failure.workerId(), failure.consecutiveFailures(), failure.cause().getMessage());
        }

        @Override
        public void onShutdownFailure(String workerId, Throwable failure) {
            System.err.printf("[java-scenario-launcher] worker shutdown failed workerId=%s error=%s%n",
                    workerId, failure.getMessage());
        }
    }
}
