package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

final class ScenarioLauncher {
    private final ScenarioLauncherOptions options;
    private final ObjectMapper objectMapper;
    private final ScenarioClientFactory clientFactory;

    ScenarioLauncher(ScenarioLauncherOptions options,
                     ObjectMapper objectMapper,
                     ScenarioClientFactory clientFactory) {
        this.options = Objects.requireNonNull(options, "options is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
    }

    void registerOnly(ScenarioFiles files) {
        System.out.println("[java-scenario-launcher] using initialized server catalog, rules, and credentials");
        WorkerScenarioRegistrar workerRegistrar = new WorkerScenarioRegistrar(options, clientFactory);
        workerRegistrar.register(files.workerSpecs(), true);
        TaskScenarioSeeder taskSeeder = new TaskScenarioSeeder(options, objectMapper, clientFactory);
        taskSeeder.seed(files.taskSpecs());
        System.out.printf("[java-scenario-launcher] register-only complete workers=%d tasks=%d%n",
                files.workerSpecs().size(), files.taskSpecs().size());
    }

    void launch(ScenarioFiles files) throws InterruptedException {
        System.out.println("[java-scenario-launcher] using initialized server catalog, rules, and credentials");
        WorkerScenarioRegistrar workerRegistrar = new WorkerScenarioRegistrar(options, clientFactory);
        workerRegistrar.register(files.workerSpecs(), false);
        ScenarioIdleTracker idleTracker = new ScenarioIdleTracker();
        try (ScenarioWorkerRuntime workerRuntime = new ScenarioWorkerRuntime(options, clientFactory, idleTracker)) {
            int startedWorkers = workerRuntime.start(files.workerSpecs());
            TaskScenarioSeeder taskSeeder = new TaskScenarioSeeder(
                    options,
                    objectMapper,
                    clientFactory,
                    workerRuntime.startedWorkerGroupIds()
            );
            List<TaskScenarioSeeder.SeededTask> seededTasks = taskSeeder.seed(files.taskSpecs());
            idleTracker.markActivity();
            if (startedWorkers == 0) {
                System.out.println("[java-scenario-launcher] no worker sessions started; launch complete");
                return;
            }
            System.out.printf("[java-scenario-launcher] launch running workerSessions=%d idleTimeoutMs=%d%n",
                    startedWorkers, options.idleTimeoutMs());
            workerRuntime.awaitShutdownOrIdle(seededTasks);
        }
    }
}
