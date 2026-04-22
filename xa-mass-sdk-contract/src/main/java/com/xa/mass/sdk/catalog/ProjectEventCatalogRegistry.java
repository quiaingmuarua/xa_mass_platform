package com.xa.mass.sdk.catalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory registry for SDK v1 project and event metadata.
 */
public class ProjectEventCatalogRegistry implements ProjectEventCatalog {

    private final Map<String, ProjectMetadata> projects = new LinkedHashMap<>();
    private final Map<String, EventMetadata> events = new LinkedHashMap<>();

    public synchronized ProjectEventCatalogRegistry registerProject(ProjectMetadata projectMetadata) {
        ProjectMetadata project = Objects.requireNonNull(projectMetadata, "projectMetadata");
        projects.put(project.getCode(), project);
        return this;
    }

    public synchronized ProjectEventCatalogRegistry registerEvent(EventMetadata eventMetadata) {
        EventMetadata event = Objects.requireNonNull(eventMetadata, "eventMetadata");
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
    public synchronized List<EventMetadata> listEvents() {
        return events.values().stream()
                .sorted(Comparator.comparing(EventMetadata::getCode, Comparator.nullsLast(String::compareTo)))
                .toList();
    }

    @Override
    public synchronized EventMetadata getEvent(String eventCode) {
        return events.get(eventCode);
    }

    @Override
    public synchronized List<EventMetadata> getEventsForProject(String projectCode) {
        ProjectMetadata project = projects.get(projectCode);
        if (project == null) {
            return List.of();
        }

        List<EventMetadata> resolved = new ArrayList<>();
        for (String eventCode : project.getEventCodes()) {
            EventMetadata eventMetadata = events.get(eventCode);
            if (eventMetadata != null) {
                resolved.add(eventMetadata);
            }
        }
        return List.copyOf(resolved);
    }
}
