package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.exception.MassHttpException;

import java.net.http.HttpClient;
import java.util.List;

public final class ScenarioTaskLauncherMain {
    private ScenarioTaskLauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(args);
        if (options.help()) {
            System.out.println(ScenarioLauncherOptions.taskHelpText());
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
            TaskScenarioSeeder taskSeeder = new TaskScenarioSeeder(options, objectMapper, clientFactory);
            List<TaskScenarioSeeder.SeededTask> seededTasks = taskSeeder.seed(files.taskSpecs());
            System.out.printf("[java-scenario-task-launcher] complete tasks=%d%n", seededTasks.size());
        } catch (MassHttpException e) {
            throw new IllegalStateException(ScenarioFailureDiagnostics.diagnoseHttpFailure(options, e), e);
        }
    }
}
