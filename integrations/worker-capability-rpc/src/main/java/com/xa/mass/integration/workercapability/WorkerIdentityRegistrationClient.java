package com.xa.mass.integration.workercapability;

import java.util.Map;

final class WorkerIdentityRegistrationClient {

    private final RuntimeApiHttpClient http;

    WorkerIdentityRegistrationClient(RuntimeApiHttpClient http) {
        this.http = http;
    }

    String registerOrRecoverWorkerId(
            String workerGroupId,
            String clientWorkerKey
    ) {
        RuntimeApiHttpClient.ApiResponse response = http.send(
                "POST",
                "/api/v1/worker-groups/"
                        + RuntimeApiHttpClient.identifier(workerGroupId)
                        + "/workers:register",
                Map.of(
                        "workerProperties",
                        Map.of(
                                "clientWorkerKey",
                                RuntimeApiHttpClient.identifier(
                                        clientWorkerKey
                                )
                        )
                )
        );
        RuntimeApiHttpClient.requireStatus(
                response,
                200,
                "workerIdentity.register"
        );
        Object value = response.body().get("workerId");
        if (!(value instanceof String) || ((String) value).isBlank()) {
            throw invalidWorkerId();
        }
        return (String) value;
    }

    private static IllegalStateException invalidWorkerId() {
        return new IllegalStateException(
                "workerIdentity.register returned an invalid workerId"
        );
    }
}
