package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectEventCatalogRegistry;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.event.SdkEventDefinition;
import com.xa.mass.sdk.event.SdkEventDefinitionRegistry;

import java.util.List;
import java.util.Objects;

/**
 * Read-only project/event catalog view backed by SDK event definitions.
 *
 * <p>Project metadata still comes from the project registry, while event
 * definitions and project-event resolution are projected from
 * {@link SdkEventDefinitionRegistry}.
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
    public List<SdkEventDefinition> listEvents() {
        return eventDefinitionRegistry.list();
    }

    @Override
    public SdkEventDefinition getEvent(String eventCode) {
        return eventDefinitionRegistry.get(eventCode);
    }

    @Override
    public List<SdkEventDefinition> getEventsForProject(String projectCode) {
        return eventDefinitionRegistry.listForProject(projectCode);
    }
}
