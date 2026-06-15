package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.exception.MassClientException;

import java.util.List;

public final class ScenarioTaskLauncherMain {
    private ScenarioTaskLauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parseTask(args);
        if (options.help()) {
            System.out.println(ScenarioLauncherOptions.taskHelpText());
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ScenarioClientFactory clientFactory = new ScenarioClientFactory(
                options.baseUrl(),
                options.connectTimeout(),
                options.requestTimeout(),
                objectMapper
        );
        try {
            TaskScenarioSeeder taskSeeder = new TaskScenarioSeeder(options, objectMapper, clientFactory);
            List<TaskScenarioSpec> taskSpecs = options.configPath() == null
                    ? ScenarioFiles.load(options.scenarioDir(), objectMapper).taskSpecs()
                    : ScenarioTaskConfigLoader.load(options.configPath(), objectMapper);
            List<TaskScenarioSeeder.SeededTask> seededTasks = taskSeeder.seed(taskSpecs);
            if (options.waitVisibleSuccess()) {
                new ScenarioTaskResultVerifier(clientFactory, options).waitForVisibleSuccess(seededTasks);
            }
            System.out.printf("[java-scenario-task-launcher] complete tasks=%d%n", seededTasks.size());
        } catch (MassClientException e) {
            throw new IllegalStateException(ScenarioFailureDiagnostics.diagnoseClientFailure(options, e), e);
        }
    }
}
