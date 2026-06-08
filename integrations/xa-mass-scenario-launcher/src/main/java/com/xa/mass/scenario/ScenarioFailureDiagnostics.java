package com.xa.mass.scenario;

import com.xa.mass.client.http.exception.MassHttpException;

final class ScenarioFailureDiagnostics {
    private ScenarioFailureDiagnostics() {
    }

    static String diagnoseHttpFailure(ScenarioLauncherOptions options, MassHttpException failure) {
        if (failure.statusCode() == 401
                && failure.path() != null
                && "POST".equals(failure.method())
                && failure.path().endsWith("/tasks")
                && failure.responseBody() != null
                && failure.responseBody().contains("Invalid or missing API-key credential")) {
            String scenarioDir = normalizedScenarioDir(options);
            return "Scenario task creation was rejected because the configured task API-key credential does not "
                    + "exist in the target server or is not valid for task creation. "
                    + "Initialize this server environment with the matching scenario catalog/rules/API keys before "
                    + "launching, or pass a real task producer key with --task-api-key / MASS_TASK_API_KEY. "
                    + sampleSeedCommand(scenarioDir);
        }
        if (failure.statusCode() == 400
                && failure.path() != null
                && failure.path().endsWith("/worker-groups")
                && failure.responseBody() != null
                && failure.responseBody().contains("Unsupported worker event")) {
            String scenarioDir = normalizedScenarioDir(options);
            return "Scenario launcher worker registration was rejected because the server catalog does not contain "
                    + "an event declared by " + scenarioDir + "/workers.json. "
                    + "Initialize this server environment with the matching scenario catalog/rules before launching. "
                    + sampleSeedCommand(scenarioDir);
        }
        return failure.getMessage();
    }

    private static String normalizedScenarioDir(ScenarioLauncherOptions options) {
        return options.scenarioDir().toString().replace('\\', '/');
    }

    private static String sampleSeedCommand(String scenarioDir) {
        return "For the checked-in local scenario seed, start the server with "
                + "--mass.control-plane.seed.enabled=true "
                + "--mass.control-plane.seed.allow-local-fixture-raw-secrets=true "
                + "--mass.control-plane.seed.catalog-location=file:" + scenarioDir + "/bootstrap.json "
                + "--mass.control-plane.seed.rules-location=file:" + scenarioDir + "/rules.json";
    }
}
