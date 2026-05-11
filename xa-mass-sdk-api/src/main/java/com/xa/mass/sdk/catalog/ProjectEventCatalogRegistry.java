package com.xa.mass.sdk.catalog;

import com.xa.mass.sdk.event.EventDefinition;

import java.util.*;

/**
 * In-memory bootstrap registry for project resources and optional SDK event
 * definition seeds.
 *
 * <p>Projects remain a control-plane directory, while any seeded SDK event
 * definitions are keyed by globally unique event code. Runtime callers should
 * not treat this bootstrap registry as the canonical event capability source
 * once the application has projected definitions from the underlying event
 * runtime.
 */
public class ProjectEventCatalogRegistry implements ProjectEventCatalog {

    private final Map<String, ProjectMetadata> projects = new LinkedHashMap<>();
    private final Map<String, EventDefinition> events = new LinkedHashMap<>();

    public synchronized ProjectEventCatalogRegistry registerProject(ProjectMetadata projectMetadata) {
        ProjectMetadata project = Objects.requireNonNull(projectMetadata, "projectMetadata");
        projects.put(project.getCode(), project);
        return this;
    }

    public synchronized ProjectEventCatalogRegistry registerEventDefinition(EventDefinition definition) {
        EventDefinition event = Objects.requireNonNull(definition, "definition");
        events.put(event.getCode(), event);
        return this;
    }

    @Override
    public synchronized List<ProjectMetadata> listProjects() {
        return projects.values().stream()
                .sorted(Comparator.comparing(ProjectMetadata::getCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @Override
    public synchronized ProjectMetadata getProject(String projectCode) {
        return projects.get(projectCode);
    }

    @Override
    public synchronized List<EventDefinition> listEvents() {
        return events.values().stream()
                .sorted(Comparator.comparing(EventDefinition::getCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @Override
    public synchronized EventDefinition getEvent(String eventCode) {
        return events.get(eventCode);
    }

    @Override
    public synchronized List<EventDefinition> getEventsForProject(String projectCode) {
        ProjectMetadata project = projects.get(projectCode);
        if (project == null) {
            return List.of();
        }

        List<EventDefinition> resolved = new ArrayList<>();
        for (String eventCode : project.getAuthorizedEventCodes()) {
            EventDefinition definition = events.get(eventCode);
            if (definition != null) {
                resolved.add(definition);
            }
        }
        return List.copyOf(resolved);
    }
}
