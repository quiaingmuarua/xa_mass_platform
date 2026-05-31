package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

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
        workerRegistrar.register(files.workerSpecs());
        TaskScenarioSeeder taskSeeder = new TaskScenarioSeeder(options, objectMapper, clientFactory);
        taskSeeder.seed(files.taskSpecs());
        System.out.printf("[java-scenario-launcher] register-only complete workers=%d tasks=%d%n",
                files.workerSpecs().size(), files.taskSpecs().size());
    }
}
