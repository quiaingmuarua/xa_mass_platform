package com.xa.mass.api.internal;

import com.xa.mass.api.model.ApiResponse;
import com.xa.mass.kernel.spi.rule.RuleDefinition;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.server.catalog.CatalogMetadataProjection;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.api.RuleStorage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/control-plane")
public class ControlPlaneInitializationController {

    private final MassSdkApplication app;
    private final CatalogMetadataStore catalogMetadataStore;
    private final RuleStorage ruleStorage;

    public ControlPlaneInitializationController(MassSdkApplication app,
                                                CatalogMetadataStore catalogMetadataStore,
                                                RuleStorage ruleStorage) {
        this.app = Objects.requireNonNull(app, "app is required");
        this.catalogMetadataStore = Objects.requireNonNull(catalogMetadataStore, "catalogMetadataStore is required");
        this.ruleStorage = Objects.requireNonNull(ruleStorage, "ruleStorage is required");
    }

    @PostMapping("/catalog:sync")
    public ResponseEntity<ApiResponse<SyncResult>> syncCatalog(@RequestBody CatalogSyncRequest request) {
        CatalogSyncRequest normalized = request == null ? new CatalogSyncRequest(List.of(), List.of()) : request;
        List<EventDefinition> events = normalized.events().stream()
                .filter(Objects::nonNull)
                .map(EventManifest::toDefinition)
                .toList();
        List<ProjectDefinition> projects = normalized.projects().stream()
                .filter(Objects::nonNull)
                .map(ProjectManifest::toDefinition)
                .toList();

        CatalogMetadataProjection.validateUpsert(catalogMetadataStore, events, projects);
        CatalogMetadataProjection.upsertCatalog(catalogMetadataStore, events, projects);
        projects.forEach(app::registerProject);
        events.forEach(app::registerEventDefinition);
        return ResponseEntity.ok(ApiResponse.success(new SyncResult(events.size(), projects.size(), 0)));
    }

    @PostMapping("/rules:sync")
    public ResponseEntity<ApiResponse<SyncResult>> syncRules(@RequestBody RuleSyncRequest request) {
        RuleSyncRequest normalized = request == null ? new RuleSyncRequest(List.of()) : request;
        List<RuleDefinition> rules = normalized.rules().stream()
                .filter(Objects::nonNull)
                .peek(ControlPlaneInitializationController::requireRuleId)
                .toList();
        ruleStorage.addRules(rules);
        return ResponseEntity.ok(ApiResponse.success(new SyncResult(0, 0, rules.size())));
    }

    private static void requireRuleId(RuleDefinition rule) {
        if (rule.getId() == null || rule.getId().isBlank()) {
            throw new IllegalArgumentException("rule id must not be blank");
        }
    }

    public record CatalogSyncRequest(List<EventManifest> events,
                                     List<ProjectManifest> projects) {
        public CatalogSyncRequest {
            events = events == null ? List.of() : List.copyOf(events);
            projects = projects == null ? List.of() : List.copyOf(projects);
        }
    }

    public record EventManifest(String code,
                                String name,
                                String description,
                                List<String> payloadTypes,
                                List<String> taskModes,
                                Boolean enabled,
                                String defaultRoutingCode,
                                List<String> projectCodes,
                                String priorityClass,
                                String responseMode,
                                String deliveryAcknowledgementMode,
                                String convergenceMode,
                                String targetScope) {

        EventDefinition toDefinition() {
            return EventDefinition.builder()
                    .code(code)
                    .name(name)
                    .description(description)
                    .payloadTypes(parseEnums(payloadTypes, PayloadType.class))
                    .taskModes(parseEnums(taskModes, TaskMode.class))
                    .enabled(enabled == null || enabled)
                    .defaultRoutingCode(defaultRoutingCode)
                    .projectCodes(projectCodes == null ? List.of() : projectCodes)
                    .priorityClassName(priorityClass)
                    .responseModeName(responseMode)
                    .deliveryAcknowledgementModeName(deliveryAcknowledgementMode)
                    .convergenceModeName(convergenceMode)
                    .targetScopeName(targetScope)
                    .build();
        }
    }

    public record ProjectManifest(String tenantId,
                                  String code,
                                  String name,
                                  String description,
                                  Boolean enabled,
                                  String ownerPrincipalId,
                                  List<String> eventCodes) {

        ProjectDefinition toDefinition() {
            return ProjectDefinition.builder()
                    .tenantId(tenantId)
                    .code(code)
                    .name(name)
                    .description(description)
                    .enabled(enabled == null || enabled)
                    .ownerPrincipalId(ownerPrincipalId)
                    .eventCodes(eventCodes == null ? List.of() : eventCodes)
                    .build();
        }
    }

    public record RuleSyncRequest(List<RuleDefinition> rules) {
        public RuleSyncRequest {
            rules = rules == null ? List.of() : List.copyOf(rules);
        }
    }

    public record SyncResult(int events, int projects, int rules) {
    }

    private static <E extends Enum<E>> List<E> parseEnums(Collection<String> values, Class<E> type) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> Enum.valueOf(type, value.toUpperCase(Locale.ROOT)))
                .distinct()
                .toList();
    }
}
