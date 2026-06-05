package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.client.http.exception.MassHttpException;

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
        HttpClient httpClient = HttpClient.newHttpClient();
        ScenarioLauncher launcher = new ScenarioLauncher(
                options,
                objectMapper,
                new ScenarioClientFactory(options.baseUrl(), httpClient, objectMapper)
        );
        try {
            if (options.registerOnly()) {
                launcher.registerOnly(files);
            } else {
                launcher.launch(files);
            }
        } catch (MassHttpException e) {
            throw new IllegalStateException(diagnoseHttpFailure(options, e), e);
        }
    }

    static String diagnoseHttpFailure(ScenarioLauncherOptions options, MassHttpException failure) {
        if (failure.statusCode() == 400
                && failure.path() != null
                && failure.path().endsWith("/worker-groups")
                && failure.responseBody() != null
                && failure.responseBody().contains("Unsupported worker event")) {
            String scenarioDir = options.scenarioDir().toString().replace('\\', '/');
            return "Scenario launcher worker registration was rejected because the server catalog does not contain "
                    + "an event declared by " + scenarioDir + "/workers.json. "
                    + "Initialize this server environment with the matching scenario catalog/rules before launching: "
                    + "--mass.control-plane.seed.enabled=true "
                    + "--mass.control-plane.seed.catalog-location=file:" + scenarioDir + "/bootstrap.json "
                    + "--mass.control-plane.seed.rules-location=file:" + scenarioDir + "/rules.json";
        }
        return failure.getMessage();
    }
}
