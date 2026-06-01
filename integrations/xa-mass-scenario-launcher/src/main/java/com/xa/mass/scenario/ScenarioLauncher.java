package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;

final class ScenarioLauncher {
    private final ScenarioLauncherOptions options;
    private final ObjectMapper objectMapper;
    private final ScenarioClientFactory clientFactory;
    private final DevBootstrapClient bootstrapClient;

    ScenarioLauncher(ScenarioLauncherOptions options,
                     ObjectMapper objectMapper,
                     ScenarioClientFactory clientFactory,
                     DevBootstrapClient bootstrapClient) {
        this.options = Objects.requireNonNull(options, "options is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory is required");
        this.bootstrapClient = Objects.requireNonNull(bootstrapClient, "bootstrapClient is required");
    }

    void registerOnly(ScenarioFiles files) {
        bootstrapClient.bootstrapCatalog(files.bootstrapSpec());
        bootstrapClient.bootstrapRules(files.ruleSpec());
        WorkerScenarioRegistrar workerRegistrar = new WorkerScenarioRegistrar(options, clientFactory);
        workerRegistrar.register(files.workerSpecs(), true);
        TaskScenarioSeeder taskSeeder = new TaskScenarioSeeder(options, objectMapper, clientFactory);
        taskSeeder.seed(files.taskSpecs());
        System.out.printf("[java-scenario-launcher] register-only complete workers=%d tasks=%d%n",
                files.workerSpecs().size(), files.taskSpecs().size());
    }

    void launch(ScenarioFiles files) throws InterruptedException {
        bootstrapClient.bootstrapCatalog(files.bootstrapSpec());
        bootstrapClient.bootstrapRules(files.ruleSpec());
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
