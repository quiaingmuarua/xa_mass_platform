package com.xa.mass.server.catalog;

import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.catalog.PayloadType;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.catalog.TaskMode;
import com.xa.mass.sdk.event.EventDefinition;
import com.xa.mass.storage.api.CatalogEventRecord;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.api.CatalogProjectRecord;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class CatalogMetadataProjection {

    private CatalogMetadataProjection() {
    }

    public static void restoreIntoApplication(CatalogMetadataStore store, MassSdkApplication app) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(app, "app");
        for (CatalogProjectRecord project : store.listProjects()) {
            app.registerProject(toProjectDefinition(project));
        }
        for (CatalogEventRecord event : store.listEvents()) {
            app.registerEventDefinition(toEventDefinition(event));
        }
    }

    public static void validateUpsert(CatalogMetadataStore store,
                                      Collection<EventDefinition> events,
                                      Collection<ProjectDefinition> projects) {
        store.validateUpsertCatalog(
                toEventRecords(events),
                toProjectRecords(projects)
        );
    }

    public static void upsertCatalog(CatalogMetadataStore store,
                                     Collection<EventDefinition> events,
                                     Collection<ProjectDefinition> projects) {
        store.upsertCatalog(
                toEventRecords(events),
                toProjectRecords(projects)
        );
    }

    public static List<CatalogEventRecord> toEventRecords(Collection<EventDefinition> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().map(CatalogMetadataProjection::toEventRecord).toList();
    }

    public static List<CatalogProjectRecord> toProjectRecords(Collection<ProjectDefinition> projects) {
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }
        return projects.stream().map(CatalogMetadataProjection::toProjectRecord).toList();
    }

    public static CatalogEventRecord toEventRecord(EventDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return new CatalogEventRecord(
                definition.getCode(),
                definition.getName(),
                definition.getDescription(),
                definition.getPayloadTypes().stream().map(Enum::name).toList(),
                definition.getTaskModes().stream().map(Enum::name).toList(),
                definition.isEnabled(),
                definition.getDefaultRoutingCode(),
                definition.getProjectCodes(),
                definition.getPriorityClass() == null ? null : definition.getPriorityClass().name(),
                definition.getResponseMode() == null ? null : definition.getResponseMode().name(),
                definition.getDeliveryAcknowledgementMode() == null
                        ? null
                        : definition.getDeliveryAcknowledgementMode().name(),
                definition.getConvergenceMode() == null ? null : definition.getConvergenceMode().name(),
                definition.getTargetScope() == null ? null : definition.getTargetScope().name()
        );
    }

    public static CatalogProjectRecord toProjectRecord(ProjectDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        return new CatalogProjectRecord(
                definition.getTenantId(),
                definition.getCode(),
                definition.getName(),
                definition.getDescription(),
                definition.isEnabled(),
                definition.getOwnerPrincipalId(),
                definition.getEventCodes()
        );
    }

    public static EventDefinition toEventDefinition(CatalogEventRecord record) {
        Objects.requireNonNull(record, "record");
        return EventDefinition.builder()
                .code(record.code())
                .name(record.name())
                .description(record.description())
                .payloadTypes(parseEnums(record.payloadTypes(), PayloadType.class))
                .taskModes(parseEnums(record.taskModes(), TaskMode.class))
                .enabled(record.enabled())
                .defaultRoutingCode(record.defaultRoutingCode())
                .projectCodes(record.projectCodes())
                .priorityClassName(record.priorityClass())
                .responseModeName(record.responseMode())
                .deliveryAcknowledgementModeName(record.deliveryAcknowledgementMode())
                .convergenceModeName(record.convergenceMode())
                .targetScopeName(record.targetScope())
                .build();
    }

    public static ProjectDefinition toProjectDefinition(CatalogProjectRecord record) {
        Objects.requireNonNull(record, "record");
        return ProjectDefinition.builder()
                .tenantId(record.tenantId())
                .code(record.code())
                .name(record.name())
                .description(record.description())
                .enabled(record.enabled())
                .ownerPrincipalId(record.ownerPrincipalId())
                .eventCodes(record.eventCodes())
                .build();
    }

    private static <E extends Enum<E>> List<E> parseEnums(List<String> values, Class<E> type) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(value -> parseEnum(value, type))
                .filter(Objects::nonNull)
                .toList();
    }

    private static <E extends Enum<E>> E parseEnum(String value, Class<E> type) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    }
}
