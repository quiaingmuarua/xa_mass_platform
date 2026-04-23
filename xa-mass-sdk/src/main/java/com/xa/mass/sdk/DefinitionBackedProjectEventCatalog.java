package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.event.SdkEventDefinition;
import com.xa.mass.sdk.event.SdkEventDefinitionRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Read-only project/event catalog view backed by SDK event definitions.
 *
 * <p>Project metadata still comes from the project registry, while event metadata and
 * project-event resolution are projected from {@link SdkEventDefinitionRegistry}.
 */
final class DefinitionBackedProjectEventCatalog implements ProjectEventCatalog {

    private final ProjectEventCatalogRegistry projectRegistry;
    private final SdkEventDefinitionRegistry eventDefinitionRegistry;

    DefinitionBackedProjectEventCatalog(ProjectEventCatalogRegistry projectRegistry,
                                        SdkEventDefinitionRegistry eventDefinitionRegistry) {
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.eventDefinitionRegistry = Objects.requireNonNull(eventDefinitionRegistry, "eventDefinitionRegistry");
    }

    @Override
    public List<ProjectMetadata> listProjects() {
        return projectRegistry.listProjects();
    }

    @Override
    public ProjectMetadata getProject(String projectCode) {
        return projectRegistry.getProject(projectCode);
    }

    @Override
    public List<EventMetadata> listEvents() {
        return eventDefinitionRegistry.listMetadata();
    }

    @Override
    public EventMetadata getEvent(String eventCode) {
        SdkEventDefinition definition = eventDefinitionRegistry.get(eventCode);
        return definition == null ? null : definition.getMetadata();
    }

    @Override
    public List<EventMetadata> getEventsForProject(String projectCode) {
        ProjectMetadata projectMetadata = projectRegistry.getProject(projectCode);
        if (projectMetadata == null) {
            return List.of();
        }
        List<EventMetadata> events = new ArrayList<>();
        for (String eventCode : projectMetadata.getEventCodes()) {
            SdkEventDefinition definition = eventDefinitionRegistry.get(eventCode);
            if (definition != null && definition.getProjectCodes().contains(projectMetadata.getCode())) {
                events.add(definition.getMetadata());
            }
        }
        events.sort(Comparator.comparing(EventMetadata::getCode, String::compareToIgnoreCase));
        return List.copyOf(events);
    }
}
