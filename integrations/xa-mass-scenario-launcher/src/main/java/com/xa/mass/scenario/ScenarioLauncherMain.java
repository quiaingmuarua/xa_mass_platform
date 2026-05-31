package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;

public final class ScenarioLauncherMain {
    private ScenarioLauncherMain() {
    }

    public static void main(String[] args) throws Exception {
        ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(args);
        if (options.help()) {
            System.out.println(ScenarioLauncherOptions.helpText());
            return;
        }
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        ScenarioFiles files = ScenarioFiles.load(options.scenarioDir(), objectMapper);
        ScenarioLauncher launcher = new ScenarioLauncher(
                options,
                objectMapper,
                new ScenarioClientFactory(options.baseUrl(), HttpClient.newHttpClient(), objectMapper),
                new DevBootstrapClient(options.baseUrl(), options.bootstrapKey(), HttpClient.newHttpClient(), objectMapper)
        );
        if (options.registerOnly()) {
            launcher.registerOnly(files);
        } else {
            launcher.launch(files);
        }
    }
}
