package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.exception.MassHttpException;

import java.net.http.HttpClient;

public final class ScenarioWorkerLauncherMain {
    private ScenarioWorkerLauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(args);
        if (options.help()) {
            System.out.println(ScenarioLauncherOptions.workerHelpText());
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ScenarioFiles files = ScenarioFiles.load(options.scenarioDir(), objectMapper);
        ScenarioClientFactory clientFactory = new ScenarioClientFactory(
                options.baseUrl(),
                HttpClient.newHttpClient(),
                objectMapper
        );
        try {
            System.out.println("[java-scenario-worker-launcher] using initialized server catalog, rules, and credentials");
            WorkerScenarioRegistrar workerRegistrar = new WorkerScenarioRegistrar(options, clientFactory);
            workerRegistrar.register(files.workerSpecs(), false);
            ScenarioIdleTracker idleTracker = new ScenarioIdleTracker();
            try (ScenarioWorkerRuntime workerRuntime = new ScenarioWorkerRuntime(options, clientFactory, idleTracker)) {
                int startedWorkers = workerRuntime.start(files.workerSpecs());
                if (startedWorkers == 0) {
                    System.out.println("[java-scenario-worker-launcher] no worker sessions started");
                    return;
                }
                System.out.printf("[java-scenario-worker-launcher] running workerSessions=%d%n", startedWorkers);
                workerRuntime.awaitShutdown();
            }
        } catch (MassHttpException e) {
            throw new IllegalStateException(ScenarioFailureDiagnostics.diagnoseHttpFailure(options, e), e);
        }
    }
}
