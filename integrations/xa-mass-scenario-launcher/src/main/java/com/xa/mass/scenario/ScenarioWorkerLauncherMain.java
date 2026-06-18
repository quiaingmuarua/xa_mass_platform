package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.exception.MassClientException;

public final class ScenarioWorkerLauncherMain {
    private ScenarioWorkerLauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parseWorker(args);
        if (options.help()) {
            System.out.println(ScenarioLauncherOptions.workerHelpText());
            return;
        }
        if (options.configPath() != null) {
            throw new IllegalArgumentException("worker launcher config mode is deferred; use --scenario-dir with workers.json");
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ScenarioFiles files = ScenarioFiles.load(options.scenarioDir(), objectMapper);
        ScenarioClientFactory clientFactory = new ScenarioClientFactory(
                options.baseUrl(),
                options.connectTimeout(),
                options.requestTimeout(),
                objectMapper
        );
        try {
            System.out.println("[java-scenario-worker-launcher] using initialized server catalog, rules, and credentials");
            WorkerScenarioRegistrar workerRegistrar = new WorkerScenarioRegistrar(options, clientFactory);
            if (options.registerApiOnlineOnly()) {
                workerRegistrar.register(files.workerSpecs(), true);
                System.out.printf("[java-scenario-worker-launcher] registered api-online workers=%d groups=%d%n",
                        files.workerSpecs().size(),
                        workerRegistrar.declaredWorkerGroupCount());
                return;
            }
            workerRegistrar.register(files.workerSpecs(), false);
            ScenarioIdleTracker idleTracker = new ScenarioIdleTracker();
            try (ScenarioWorkerRuntime workerRuntime = new ScenarioWorkerRuntime(options, clientFactory, idleTracker)) {
                int startedWorkers = workerRuntime.start(files.workerSpecs());
                if (startedWorkers == 0) {
                    System.out.println("[java-scenario-worker-launcher] no worker sessions started");
                    return;
                }
                System.out.printf("[java-scenario-worker-launcher] running workerRuntimes=%d%n", startedWorkers);
                workerRuntime.awaitShutdown();
            }
        } catch (MassClientException e) {
            throw new IllegalStateException(ScenarioFailureDiagnostics.diagnoseClientFailure(options, e), e);
        }
    }
}
