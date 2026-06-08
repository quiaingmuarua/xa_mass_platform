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
            return "Scenario task creation was rejected because the configured task API-key credential does not "
                    + "exist in the target server or is not valid for task creation. "
                    + "Initialize this server environment with the matching scenario catalog/rules before "
                    + "launching, or pass a real task producer key with --task-api-key / MASS_TASK_API_KEY. "
                    + "For local scenario config, run xa-mass-scenario-credential-bootstrap with the same --config "
                    + "to validate or create credentials.taskApiKeyFile. "
                    + localSeedCommand();
        }
        if (failure.statusCode() == 400
                && failure.path() != null
                && failure.path().endsWith("/worker-groups")
                && failure.responseBody() != null
                && failure.responseBody().contains("Unsupported worker event")) {
            return "Scenario launcher worker registration was rejected because the server catalog does not contain "
                    + "an event declared by " + normalizedScenarioDir(options) + "/workers.json. "
                    + "Initialize this server environment with the matching scenario catalog/rules before launching. "
                    + localSeedCommand();
        }
        if (failure.statusCode() == 401
                && failure.path() != null
                && failure.path().startsWith("/worker-api/v1/")
                && failure.responseBody() != null
                && failure.responseBody().contains("Invalid or missing worker credential")) {
            return "Scenario worker registration was rejected because the configured worker API-key credential "
                    + "does not exist in the target server or is not valid for worker registration/polling. "
                    + "For local scenario runs, create a worker credential with "
                    + "xa-mass-scenario-credential-bootstrap --kind worker --api-key-file "
                    + "integrations/xa-mass-scenario-launcher/examples/secrets/worker-api-key.txt, "
                    + "or pass a real worker key with --worker-api-key / --worker-api-key-file / MASS_WORKER_API_KEY. "
                    + localSeedCommand();
        }
        return failure.getMessage();
    }

    private static String normalizedScenarioDir(ScenarioLauncherOptions options) {
        return options.scenarioDir().toString().replace('\\', '/');
    }

    private static String localSeedCommand() {
        return "For the checked-in local scenario seed, start the server with "
                + "--mass.control-plane.seed.enabled=true "
                + "--mass.control-plane.seed.catalog-location=file:integrations/xa-mass-scenario-launcher/"
                + "examples/scenario.catalog.seed.json "
                + "--mass.control-plane.seed.rules-location=file:integrations/samples/dev/scenario/rules.json "
                + "--mass.control-plane.seed.operator-credentials-location="
                + "classpath:control-plane-seed/operator-credentials.json";
    }
}
