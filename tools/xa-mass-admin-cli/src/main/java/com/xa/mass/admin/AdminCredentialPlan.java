package com.xa.mass.admin;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record DesiredApiKey(String principalId,
                     String createdForUserId,
                     List<String> permissions,
                     List<String> projectScopes,
                     List<String> eventScopes,
                     Map<String, String> attributes,
                     String rawSecret,
                     Path cacheFile) {
    DesiredApiKey {
        permissions = permissions == null ? List.of() : List.copyOf(permissions);
        projectScopes = projectScopes == null ? List.of() : List.copyOf(projectScopes);
        eventScopes = eventScopes == null ? List.of() : List.copyOf(eventScopes);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    Map<String, Object> createRequestBody() {
        Map<String, Object> body = desiredStateBody();
        if (rawSecret != null && !rawSecret.isBlank()) {
            body.put("rawSecret", rawSecret);
        }
        return body;
    }

    Map<String, Object> desiredStateBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("principalId", principalId);
        body.put("createdForUserId", createdForUserId);
        body.put("projectScopes", projectScopes);
        body.put("eventScopes", eventScopes);
        body.put("permissions", permissions);
        body.put("attributes", attributes);
        return body;
    }
}

final class AdminCredentialPlan {
    private AdminCredentialPlan() {
    }

    static DesiredApiKey task(AdminEnvConfig.Loaded loaded) {
        AdminEnvConfig.TaskCredentialConfig task = loaded.config().credentials().taskCredential();
        return new DesiredApiKey(
                task.principalId(),
                task.createdForUserId(),
                task.permissions(),
                task.projectScopes(),
                task.eventScopes(),
                task.attributes(),
                loaded.taskRawSecret(),
                loaded.resolve(task.apiKeyFile(), "credentials.taskCredential.apiKeyFile")
        );
    }

    static List<DesiredApiKey> workers(AdminEnvConfig.Loaded loaded, List<WorkerScenarioSpec> workers) {
        AdminEnvConfig.WorkerCredentialPolicyConfig policy = loaded.config().credentials().workerCredentials();
        return workers.stream()
                .map(worker -> workerCredential(policy, worker))
                .toList();
    }

    private static DesiredApiKey workerCredential(AdminEnvConfig.WorkerCredentialPolicyConfig policy,
                                                  WorkerScenarioSpec worker) {
        String workerId = AdminEnvConfig.required(worker.workerId(), "worker.workerId");
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put(policy.workerIdAttribute(), workerId);
        List<String> projectScopes = policy.deriveProjectScopesFromWorkerBindings()
                ? worker.projectCodes()
                : policy.projectScopes();
        List<String> eventScopes = policy.deriveEventScopesFromWorkerBindings()
                ? worker.eventCodes()
                : policy.eventScopes();
        String rawSecret = switch (policy.rawSecretSource()) {
            case "workerSpec.workerKey" -> AdminEnvConfig.required(worker.workerKey(), "worker.workerKey");
            default -> throw new IllegalArgumentException(
                    "unsupported credentials.workerCredentials.rawSecretSource: " + policy.rawSecretSource());
        };
        return new DesiredApiKey(
                policy.principalIdTemplate().replace("${workerId}", workerId),
                policy.createdForUserId(),
                policy.permissions(),
                projectScopes,
                eventScopes,
                attributes,
                rawSecret,
                null
        );
    }
}
