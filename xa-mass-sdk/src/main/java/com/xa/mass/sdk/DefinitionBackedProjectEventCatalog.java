package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.event.EventDefinition;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Read-only project/event catalog view backed by the SDK application's current
 * project registry and runtime-projected event definitions.
 *
 * <p>Project metadata still comes from the project registry, while the global
 * event catalog is projected from the runtime event truth. Project membership
 * is scope metadata on top of the event definition; the event identity itself
 * remains the globally unique {@code code}.
 */
final class DefinitionBackedProjectEventCatalog implements ProjectEventCatalog {

    private final Supplier<List<ProjectMetadata>> listProjectsSupplier;
    private final Function<String, ProjectMetadata> getProjectFunction;
    private final Supplier<List<EventDefinition>> listEventsSupplier;
    private final Function<String, EventDefinition> getEventFunction;
    private final Function<String, List<EventDefinition>> getEventsForProjectFunction;

    DefinitionBackedProjectEventCatalog(Supplier<List<ProjectMetadata>> listProjectsSupplier,
                                        Function<String, ProjectMetadata> getProjectFunction,
                                        Supplier<List<EventDefinition>> listEventsSupplier,
                                        Function<String, EventDefinition> getEventFunction,
                                        Function<String, List<EventDefinition>> getEventsForProjectFunction) {
        this.listProjectsSupplier = Objects.requireNonNull(listProjectsSupplier, "listProjectsSupplier");
        this.getProjectFunction = Objects.requireNonNull(getProjectFunction, "getProjectFunction");
        this.listEventsSupplier = Objects.requireNonNull(listEventsSupplier, "listEventsSupplier");
        this.getEventFunction = Objects.requireNonNull(getEventFunction, "getEventFunction");
        this.getEventsForProjectFunction = Objects.requireNonNull(getEventsForProjectFunction, "getEventsForProjectFunction");
    }

    @Override
    public List<ProjectMetadata> listProjects() {
        return listProjectsSupplier.get();
    }

    @Override
    public ProjectMetadata getProject(String projectCode) {
        return getProjectFunction.apply(projectCode);
    }

    @Override
    public List<EventDefinition> listEvents() {
        return listEventsSupplier.get();
    }

    @Override
    public EventDefinition getEvent(String eventCode) {
        return getEventFunction.apply(eventCode);
    }

    @Override
    public List<EventDefinition> getEventsForProject(String projectCode) {
        return getEventsForProjectFunction.apply(projectCode);
    }
}
