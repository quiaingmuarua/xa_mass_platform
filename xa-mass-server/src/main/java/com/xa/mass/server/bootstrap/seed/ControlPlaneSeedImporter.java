package com.xa.mass.server.bootstrap.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.operator.OperatorCredentialRecord;
import com.xa.mass.api.auth.operator.OperatorCredentialStore;
import com.xa.mass.server.catalog.CatalogMetadataProjection;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.storage.api.CatalogMetadataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class ControlPlaneSeedImporter {
    private static final Logger log = LoggerFactory.getLogger(ControlPlaneSeedImporter.class);

    private final MassSdkApplication app;
    private final CatalogMetadataStore catalogMetadataStore;
    private final OperatorCredentialStore operatorCredentialStore;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    ControlPlaneSeedImporter(MassSdkApplication app,
                             CatalogMetadataStore catalogMetadataStore,
                             OperatorCredentialStore operatorCredentialStore,
                             ObjectMapper objectMapper,
                             ResourceLoader resourceLoader) {
        this.app = Objects.requireNonNull(app, "app is required");
        this.catalogMetadataStore = Objects.requireNonNull(catalogMetadataStore, "catalogMetadataStore is required");
        this.operatorCredentialStore = Objects.requireNonNull(operatorCredentialStore,
                "operatorCredentialStore is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader is required");
    }

    SeedImportResult importSeed(ControlPlaneSeedImportRequest request) {
        Objects.requireNonNull(request, "request is required");
        if (!request.hasAnyLocation()) {
            throw new IllegalArgumentException("control-plane seed is enabled but no seed location is configured");
        }
        ControlPlaneSeedCatalog catalog = request.catalogLocation() == null
                ? new ControlPlaneSeedCatalog()
                : read(request.catalogLocation(), ControlPlaneSeedCatalog.class);
        ControlPlaneSeedRules rules = request.rulesLocation() == null
                ? new ControlPlaneSeedRules()
                : read(request.rulesLocation(), ControlPlaneSeedRules.class);
        ControlPlaneOperatorCredentialSeed credentialSeed = request.operatorCredentialsLocation() == null
                ? new ControlPlaneOperatorCredentialSeed()
                : read(request.operatorCredentialsLocation(), ControlPlaneOperatorCredentialSeed.class);

        List<EventDefinition> events = toEventDefinitions(catalog);
        List<ProjectDefinition> projects = toProjectDefinitions(catalog);
        List<SubmitterRegistration> submitters = toSubmitterRegistrations(catalog);
        List<RuleDefinition> ruleDefinitions = List.copyOf(rules.getRules());
        List<OperatorCredentialRecord> operatorCredentials = toOperatorCredentials(credentialSeed);

        SeedImportResult result = new SeedImportResult(
                events.size(),
                projects.size(),
                submitters.size(),
                ruleDefinitions.size(),
                operatorCredentials.size()
        );
        CatalogMetadataProjection.validateUpsert(catalogMetadataStore, events, projects);
        if (request.validateOnly()) {
            log.info("Validated control-plane seed catalogLocation={} rulesLocation={} operatorCredentialsLocation={} result={}",
                    request.catalogLocation(), request.rulesLocation(), request.operatorCredentialsLocation(), result);
            return result;
        }

        CatalogMetadataProjection.upsertCatalog(catalogMetadataStore, events, projects);
        events.forEach(app::registerEventDefinition);
        projects.forEach(app::registerProject);
        submitters.forEach(app::registerSubmitter);
        if (!ruleDefinitions.isEmpty()) {
            app.replaceDefaultRules(ruleDefinitions);
        }
        operatorCredentials.forEach(operatorCredentialStore::upsert);
        log.info("Applied control-plane seed catalogLocation={} rulesLocation={} operatorCredentialsLocation={} result={}",
                request.catalogLocation(), request.rulesLocation(), request.operatorCredentialsLocation(), result);
        return result;
    }

    private <T> T read(String location, Class<T> type) {
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("control-plane seed resource does not exist: " + location);
        }
        try (var input = resource.getInputStream()) {
            return objectMapper.readValue(input, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to read control-plane seed resource: " + location, e);
        }
    }

    private List<EventDefinition> toEventDefinitions(ControlPlaneSeedCatalog catalog) {
        List<EventDefinition> definitions = new ArrayList<>();
        for (ControlPlaneSeedCatalog.EventSeed seed : catalog.getEvents()) {
            for (int index = 0; index < count(seed.getCount()); index++) {
                definitions.add(toEventDefinition(seed, placeholderValues(index)));
            }
        }
        return List.copyOf(definitions);
    }

    private List<ProjectDefinition> toProjectDefinitions(ControlPlaneSeedCatalog catalog) {
        List<ProjectDefinition> definitions = new ArrayList<>();
        for (ControlPlaneSeedCatalog.ProjectSeed seed : catalog.getProjects()) {
            for (int index = 0; index < count(seed.getCount()); index++) {
                definitions.add(toProjectDefinition(seed, placeholderValues(index)));
            }
        }
        return List.copyOf(definitions);
    }

    private List<SubmitterRegistration> toSubmitterRegistrations(ControlPlaneSeedCatalog catalog) {
        List<SubmitterRegistration> registrations = new ArrayList<>();
        for (ControlPlaneSeedCatalog.SubmitterSeed seed : catalog.getSubmitters()) {
            for (int index = 0; index < count(seed.getCount()); index++) {
                registrations.add(toSubmitterRegistration(seed, placeholderValues(index)));
            }
        }
        return List.copyOf(registrations);
    }

    private List<OperatorCredentialRecord> toOperatorCredentials(ControlPlaneOperatorCredentialSeed seed) {
        return seed.getOperatorCredentials().stream()
                .map(ControlPlaneOperatorCredentialSeed.CredentialSeed::toRecord)
                .toList();
    }

    private EventDefinition toEventDefinition(ControlPlaneSeedCatalog.EventSeed seed, Map<String, String> placeholders) {
        return EventDefinition.builder()
                .code(replace(seed.getCode(), placeholders))
                .name(replace(seed.getName(), placeholders))
                .description(replace(seed.getDescription(), placeholders))
                .payloadTypes(replace(seed.getPayloadTypes(), placeholders).stream()
                        .map(value -> enumValue(PayloadType.class, value, "payloadTypes"))
                        .toList())
                .taskModes(replace(seed.getTaskModes(), placeholders).stream()
                        .map(value -> enumValue(TaskMode.class, value, "taskModes"))
                        .toList())
                .enabled(seed.isEnabled())
                .defaultRoutingCode(replace(seed.getDefaultRoutingCode(), placeholders))
                .projectCodes(replace(seed.getProjectCodes(), placeholders))
                .build();
    }

    private ProjectDefinition toProjectDefinition(ControlPlaneSeedCatalog.ProjectSeed seed, Map<String, String> placeholders) {
        return ProjectDefinition.builder()
                .code(replace(seed.getCode(), placeholders))
                .name(replace(seed.getName(), placeholders))
                .description(replace(seed.getDescription(), placeholders))
                .enabled(seed.isEnabled())
                .eventCodes(replace(seed.getEventCodes(), placeholders))
                .build();
    }

    private SubmitterRegistration toSubmitterRegistration(ControlPlaneSeedCatalog.SubmitterSeed seed,
                                                          Map<String, String> placeholders) {
        return SubmitterRegistration.builder()
                .principalId(replace(seed.getPrincipalId(), placeholders))
                .credential(replace(seed.getCredential(), placeholders))
                .keyPrefix(replace(seed.getKeyPrefix(), placeholders))
                .userId(replace(seed.getUserId(), placeholders))
                .projectScope(replace(seed.getProjectScope(), placeholders))
                .permissions(replace(seed.getPermissions(), placeholders))
                .projectScopes(replace(seed.getProjectScopes(), placeholders))
                .eventScopes(replace(seed.getEventScopes(), placeholders))
                .enabled(seed.isEnabled())
                .attributes(replace(seed.getAttributes(), placeholders))
                .build();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " contains blank enum value");
        }
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }

    private static int count(int configuredCount) {
        return configuredCount > 0 ? configuredCount : 1;
    }

    private static Map<String, String> placeholderValues(int index) {
        String[] regions = {"us", "gb", "de", "fr", "sg", "jp"};
        String[] fingerprints = {"fp-sg-alpha", "fp-sg-beta", "fp-sg-gamma", "fp-sg-delta"};
        String[] mccMncs = {"52501", "52505"};
        return Map.of(
                "INDEX", String.valueOf(index),
                "INDEX1", String.valueOf(index + 1),
                "PAD3", String.format("%03d", index + 1),
                "PAD5", String.format("%05d", index + 1),
                "PAD6", String.format("%06d", index + 1),
                "REGION", regions[index % regions.length],
                "FINGERPRINT", fingerprints[index % fingerprints.length],
                "MCC_MNC", mccMncs[index % mccMncs.length]
        );
    }

    private static List<String> replace(List<String> values, Map<String, String> placeholders) {
        return values.stream().map(value -> replace(value, placeholders)).toList();
    }

    private static Map<String, String> replace(Map<String, String> values, Map<String, String> placeholders) {
        return values.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> replace(entry.getKey(), placeholders),
                        entry -> replace(entry.getValue(), placeholders)
                ));
    }

    private static String replace(String value, Map<String, String> placeholders) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    record ControlPlaneSeedImportRequest(String catalogLocation,
                                         String rulesLocation,
                                         String operatorCredentialsLocation,
                                         String mode) {
        boolean hasAnyLocation() {
            return catalogLocation != null || rulesLocation != null || operatorCredentialsLocation != null;
        }

        boolean validateOnly() {
            if ("apply".equalsIgnoreCase(mode)) {
                return false;
            }
            if ("validate".equalsIgnoreCase(mode)) {
                return true;
            }
            throw new IllegalArgumentException("mass.control-plane.seed.mode must be apply or validate: " + mode);
        }
    }

    record SeedImportResult(int events, int projects, int submitters, int rules, int operatorCredentials) {
    }
}
