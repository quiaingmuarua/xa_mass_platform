package com.xa.mass.scenario;

import com.xa.mass.client.http.exception.MassHttpException;

final class ScenarioFailureDiagnostics {
    private ScenarioFailureDiagnostics() {
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
