package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.ControlPlaneCatalog;
import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.event.EventDefinition;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Read-only project/event catalog view backed by the SDK application's current
 * project registry and runtime-projected event definitions.
 *
 * <p>Project definitions still come from the project registry, while the
 * global event catalog is projected from the runtime event truth. Project
 * membership is scope data on top of the event definition; the event identity
 * itself remains the globally unique {@code code}.
 */
final class DefinitionBackedControlPlaneCatalog implements ControlPlaneCatalog {

    private final Supplier<List<ProjectDefinition>> listProjectsSupplier;
    private final Function<String, ProjectDefinition> getProjectFunction;
    private final Supplier<List<EventDefinition>> listEventsSupplier;
    private final Function<String, EventDefinition> getEventFunction;
    private final Function<String, List<EventDefinition>> getEventsForProjectFunction;

    DefinitionBackedControlPlaneCatalog(Supplier<List<ProjectDefinition>> listProjectsSupplier,
                                        Function<String, ProjectDefinition> getProjectFunction,
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
    public List<ProjectDefinition> listProjects() {
        return listProjectsSupplier.get();
    }

    @Override
    public ProjectDefinition getProject(String projectCode) {
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
