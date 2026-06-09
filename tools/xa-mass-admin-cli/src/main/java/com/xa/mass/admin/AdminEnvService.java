package com.xa.mass.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class AdminEnvService {
    private final ObjectMapper objectMapper;
    private final Clock clock;

    AdminEnvService(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    AdminEnvReport verify(AdminEnvConfig.Loaded loaded) {
        Context context = context(loaded);
        AdminHttpClient client = client(loaded);
        login(client, loaded);
        verifyDesiredState(client, context);
        return new AdminEnvReport("VERIFY", false, false, "environment verified");
    }

    AdminEnvReport init(AdminEnvConfig.Loaded loaded) {
        if (loaded.config().environment().mode() == AdminEnvConfig.EnvInitMode.VERIFY) {
            return verify(loaded);
        }
        if (loaded.config().environment().mode() == AdminEnvConfig.EnvInitMode.APPLY_IF_EMPTY
                || loaded.config().environment().mode() == AdminEnvConfig.EnvInitMode.RESET_AND_APPLY) {
            throw new IllegalArgumentException("environment.mode=" + loaded.config().environment().mode()
                    + " is deferred and not implemented in the mainline env init slice");
        }

        Context context = context(loaded);
        Path markerFile = markerFile(loaded);
        AdminEnvMarker expected = context.expectedMarker();
        if (loaded.config().state().mode() == AdminEnvConfig.EnvStateMode.FILE) {
            AdminEnvMarker current = AdminEnvMarker.read(markerFile, objectMapper);
            if (current != null && current.matches(expected)) {
                AdminHttpClient client = client(loaded);
                login(client, loaded);
                verifyDesiredState(client, context);
                AdminEnvMarker.write(markerFile, current.verifiedAt(clock.instant()), objectMapper);
                return new AdminEnvReport("APPLY", false, true, "marker matched and environment verified");
            }
        }

        AdminHttpClient client = client(loaded);
        login(client, loaded);
        applyDesiredState(client, context);
        Context verifiedContext = context(loaded);
        verifyDesiredState(client, verifiedContext);
        if (loaded.config().state().mode() == AdminEnvConfig.EnvStateMode.FILE) {
            AdminEnvMarker.write(markerFile, verifiedContext.expectedMarker().verifiedAt(clock.instant()), objectMapper);
        }
        return new AdminEnvReport("APPLY", true, false, "environment applied and verified");
    }

    private Context context(AdminEnvConfig.Loaded loaded) {
        Path catalogManifest = loaded.resolve(loaded.config().environment().catalogManifest(),
                "environment.catalogManifest");
        Path rulesManifest = loaded.resolve(loaded.config().environment().rulesManifest(),
                "environment.rulesManifest");
        Path workerSpec = loaded.resolve(loaded.config().credentials().workerCredentials().workerSpecFile(),
                "credentials.workerCredentials.workerSpecFile");
        JsonNode catalog = readJson(catalogManifest, "catalog manifest");
        JsonNode rules = readJson(rulesManifest, "rules manifest");
        DesiredApiKey taskCredential = AdminCredentialPlan.task(loaded);
        List<WorkerScenarioSpec> workers = WorkerScenarioManifest.load(
                workerSpec,
                objectMapper,
                loaded.config().credentials().workerCredentials().maxWorkers()
        );
        List<DesiredApiKey> workerCredentials = AdminCredentialPlan.workers(loaded, workers);
        AdminEnvMarker expectedMarker = AdminEnvMarker.expected(
                loaded,
                catalogManifest,
                rulesManifest,
                workerSpec,
                taskCredential,
                clock.instant(),
                objectMapper
        );
        return new Context(loaded, catalogManifest, rulesManifest, workerSpec, catalog, rules,
                taskCredential, workerCredentials, expectedMarker);
    }

    private AdminHttpClient client(AdminEnvConfig.Loaded loaded) {
        return new AdminHttpClient(
                loaded.config().server().baseUrl(),
                loaded.config().server().connectTimeout(),
                loaded.config().server().requestTimeout(),
                objectMapper
        );
    }

    private void login(AdminHttpClient client, AdminEnvConfig.Loaded loaded) {
        client.health();
        AuthConfig authConfig = client.authConfig();
        if (!"session".equalsIgnoreCase(authConfig.authMode()) || !authConfig.sessionCookieSupported()) {
            throw new EnvInitFailure("operator-auth/readiness",
                    "env init proof requires session operator auth; actual authMode=" + authConfig.authMode());
        }
        client.login(loaded.config().operator().user(), loaded.operatorPassword());
        client.requireCurrentOperator();
    }

    private void verifyDesiredState(AdminHttpClient client, Context context) {
        verifyProjects(client, context.loaded().config().verify().requiredProjects());
        verifyEvents(client, context.loaded().config().verify().requiredEvents());
        verifyRules(client, ruleIds(context.rules()));
        verifyCredential(client, context.taskCredential(), "task-key");
        for (DesiredApiKey workerCredential : context.workerCredentials()) {
            verifyCredential(client, workerCredential, "worker-key");
        }
    }

    private void applyDesiredState(AdminHttpClient client, Context context) {
        client.syncCatalog(context.catalog());
        client.syncRules(context.rules());
        ensureCredential(client, context.taskCredential(), true);
        for (DesiredApiKey workerCredential : context.workerCredentials()) {
            ensureCredential(client, workerCredential, true);
        }
    }

    private void verifyProjects(AdminHttpClient client, List<String> requiredProjects) {
        Set<String> existing = valuesByField(client.getProjects(), "code");
        for (String project : requiredProjects) {
            if (!existing.contains(project)) {
                throw new EnvInitFailure("catalog/project", "missing required project: " + project);
            }
        }
    }

    private void verifyEvents(AdminHttpClient client, List<String> requiredEvents) {
        Set<String> existing = valuesByField(client.getEvents(), "code");
        for (String event : requiredEvents) {
            if (!existing.contains(event)) {
                throw new EnvInitFailure("catalog/event", "missing required event: " + event);
            }
        }
    }

    private void verifyRules(AdminHttpClient client, Set<String> requiredRules) {
        if (requiredRules.isEmpty()) {
            return;
        }
        JsonNode data = client.getRules();
        JsonNode items = data.path("items");
        Set<String> existing = valuesByField(items.isMissingNode() ? data : items, "id");
        for (String rule : requiredRules) {
            if (!existing.contains(rule)) {
                throw new EnvInitFailure("rule", "missing required rule: " + rule);
            }
        }
    }

    private void verifyCredential(AdminHttpClient client, DesiredApiKey desired, String category) {
        CurrentApiKey current = client.currentApiKey(desired.rawSecret());
        if (current == null) {
            throw new EnvInitFailure(category, "missing or invalid API key for principal " + desired.principalId());
        }
        if (!current.matches(desired)) {
            throw new EnvInitFailure(category, "stale/mismatched API key for principal " + desired.principalId());
        }
    }

    private void ensureCredential(AdminHttpClient client, DesiredApiKey desired, boolean writeCache) {
        CurrentApiKey current = client.currentApiKey(desired.rawSecret());
        if (current != null && current.matches(desired)) {
            writeCacheFile(desired, desired.rawSecret(), writeCache);
            return;
        }
        if (current != null && current.keyId() != null && !current.keyId().isBlank()) {
            client.revokeApiKey(current.keyId(), "admin env init stale credential repair");
        }
        String rawSecret = client.createApiKey(desired);
        writeCacheFile(desired, rawSecret, writeCache);
    }

    private void writeCacheFile(DesiredApiKey desired, String rawSecret, boolean writeCache) {
        if (!writeCache || desired.cacheFile() == null) {
            return;
        }
        try {
            Path parent = desired.cacheFile().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(desired.cacheFile(), rawSecret + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to write API-key cache file: " + desired.cacheFile(), e);
        }
    }

    private JsonNode readJson(Path path, String description) {
        try {
            return objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read " + description + ": " + path, e);
        }
    }

    private Path markerFile(AdminEnvConfig.Loaded loaded) {
        return loaded.config().state().mode() == AdminEnvConfig.EnvStateMode.FILE
                ? loaded.resolve(loaded.config().state().markerFile(), "state.markerFile")
                : null;
    }

    private Set<String> ruleIds(JsonNode rulesManifest) {
        JsonNode rules = rulesManifest.path("rules");
        if (!rules.isArray()) {
            return Set.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        rules.forEach(rule -> {
            String id = rule.path("id").asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
        });
        return ids;
    }

    private Set<String> valuesByField(JsonNode node, String field) {
        Set<String> values = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item.path(field).asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
        }
        return values;
    }

    record Context(AdminEnvConfig.Loaded loaded,
                   Path catalogManifest,
                   Path rulesManifest,
                   Path workerSpec,
                   JsonNode catalog,
                   JsonNode rules,
                   DesiredApiKey taskCredential,
                   List<DesiredApiKey> workerCredentials,
                   AdminEnvMarker expectedMarker) {
    }
}

record AdminEnvReport(String mode, boolean applied, boolean markerMatched, String message) {
}

final class EnvInitFailure extends RuntimeException {
    private final String category;

    EnvInitFailure(String category, String message) {
        super(category + ": " + message);
        this.category = category;
    }

    String category() {
        return category;
    }
}
