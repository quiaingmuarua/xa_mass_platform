package com.xa.mass.storage.memory;

import com.xa.mass.storage.api.CatalogEventRecord;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.api.CatalogProjectRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * In-memory project/event catalog metadata store.
 */
public class InMemoryCatalogMetadataStore implements CatalogMetadataStore {

    private final Map<String, CatalogEventRecord> eventsByCode = new LinkedHashMap<>();
    private final Map<String, CatalogProjectRecord> projectsByCode = new LinkedHashMap<>();
    private final Map<String, LinkedHashSet<String>> eventCodesByProject = new LinkedHashMap<>();

    @Override
    public synchronized void upsertEvent(CatalogEventRecord event) {
        upsertCatalog(List.of(event), List.of());
    }

    @Override
    public synchronized void upsertProject(CatalogProjectRecord project) {
        upsertCatalog(List.of(), List.of(project));
    }

    @Override
    public synchronized void validateUpsertCatalog(Collection<CatalogEventRecord> events,
                                                   Collection<CatalogProjectRecord> projects) {
        List<CatalogEventRecord> eventList = normalizeEvents(events);
        List<CatalogProjectRecord> projectList = normalizeProjects(projects);
        validateReferences(eventList, projectList);
    }

    @Override
    public synchronized void upsertCatalog(Collection<CatalogEventRecord> events,
                                           Collection<CatalogProjectRecord> projects) {
        List<CatalogEventRecord> eventList = normalizeEvents(events);
        List<CatalogProjectRecord> projectList = normalizeProjects(projects);
        validateReferences(eventList, projectList);

        for (CatalogEventRecord event : eventList) {
            eventsByCode.put(event.code(), event);
            removeEventBindings(event.code());
        }
        for (CatalogProjectRecord project : projectList) {
            projectsByCode.put(project.code(), project);
            eventCodesByProject.put(project.code(), new LinkedHashSet<>());
        }
        for (CatalogEventRecord event : eventList) {
            for (String projectCode : event.projectCodes()) {
                addBinding(projectCode, event.code());
            }
        }
        for (CatalogProjectRecord project : projectList) {
            for (String eventCode : project.eventCodes()) {
                addBinding(project.code(), eventCode);
            }
        }
    }

    @Override
    public synchronized Optional<CatalogEventRecord> getEvent(String eventCode) {
        CatalogEventRecord event = eventsByCode.get(eventCode);
        return event == null ? Optional.empty() : Optional.of(withProjectCodes(event));
    }

    @Override
    public synchronized Optional<CatalogProjectRecord> getProject(String projectCode) {
        CatalogProjectRecord project = projectsByCode.get(projectCode);
        return project == null ? Optional.empty() : Optional.of(withEventCodes(project));
    }

    @Override
    public synchronized List<CatalogEventRecord> listEvents() {
        return eventsByCode.values().stream()
                .map(this::withProjectCodes)
                .sorted(Comparator.comparing(CatalogEventRecord::code))
                .toList();
    }

    @Override
    public synchronized List<CatalogProjectRecord> listProjects() {
        return projectsByCode.values().stream()
                .map(this::withEventCodes)
                .sorted(Comparator.comparing(CatalogProjectRecord::code))
                .toList();
    }

    @Override
    public synchronized void clear() {
        eventsByCode.clear();
        projectsByCode.clear();
        eventCodesByProject.clear();
    }

    private void validateReferences(List<CatalogEventRecord> events, List<CatalogProjectRecord> projects) {
        Set<String> knownEvents = new LinkedHashSet<>(eventsByCode.keySet());
        events.stream().map(CatalogEventRecord::code).forEach(knownEvents::add);

        Set<String> knownProjects = new LinkedHashSet<>(projectsByCode.keySet());
        projects.stream().map(CatalogProjectRecord::code).forEach(knownProjects::add);

        for (CatalogProjectRecord project : projects) {
            for (String eventCode : project.eventCodes()) {
                if (!knownEvents.contains(eventCode)) {
                    throw new IllegalArgumentException("project " + project.code()
                            + " references unknown event code " + eventCode);
                }
            }
        }
        for (CatalogEventRecord event : events) {
            for (String projectCode : event.projectCodes()) {
                if (!knownProjects.contains(projectCode)) {
                    throw new IllegalArgumentException("event " + event.code()
                            + " references unknown project code " + projectCode);
                }
            }
        }
    }

    private void addBinding(String projectCode, String eventCode) {
        if (!projectsByCode.containsKey(projectCode) || !eventsByCode.containsKey(eventCode)) {
            return;
        }
        eventCodesByProject.computeIfAbsent(projectCode, ignored -> new LinkedHashSet<>()).add(eventCode);
    }

    private void removeEventBindings(String eventCode) {
        for (LinkedHashSet<String> eventCodes : eventCodesByProject.values()) {
            eventCodes.remove(eventCode);
        }
    }

    private CatalogEventRecord withProjectCodes(CatalogEventRecord event) {
        List<String> projectCodes = eventCodesByProject.entrySet().stream()
                .filter(entry -> entry.getValue().contains(event.code()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
        return new CatalogEventRecord(
                event.code(),
                event.name(),
                event.description(),
                event.payloadTypes(),
                event.taskModes(),
                event.enabled(),
                event.defaultRoutingCode(),
                projectCodes,
                event.priorityClass(),
                event.responseMode(),
                event.deliveryAcknowledgementMode(),
                event.convergenceMode(),
                event.targetScope()
        );
    }

    private CatalogProjectRecord withEventCodes(CatalogProjectRecord project) {
        List<String> eventCodes = new ArrayList<>(eventCodesByProject.getOrDefault(project.code(), new LinkedHashSet<>()));
        eventCodes.sort(String::compareTo);
        return new CatalogProjectRecord(
                project.tenantId(),
                project.code(),
                project.name(),
                project.description(),
                project.enabled(),
                project.ownerPrincipalId(),
                eventCodes
        );
    }

    private static List<CatalogEventRecord> normalizeEvents(Collection<CatalogEventRecord> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        return events.stream().toList();
    }

    private static List<CatalogProjectRecord> normalizeProjects(Collection<CatalogProjectRecord> projects) {
        if (projects == null || projects.isEmpty()) {
            return List.of();
        }
        return projects.stream().toList();
    }
}
