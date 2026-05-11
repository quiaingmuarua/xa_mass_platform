package com.xa.mass.sdk.catalog;

import com.xa.mass.sdk.event.EventDefinition;

import java.util.List;

/**
 * Read surface for the SDK control-plane project directory and
 * runtime-projected event catalog.
 */
public interface ControlPlaneCatalog {

    List<ProjectDefinition> listProjects();

    ProjectDefinition getProject(String projectCode);

    List<EventDefinition> listEvents();

    EventDefinition getEvent(String eventCode);

    List<EventDefinition> getEventsForProject(String projectCode);
}
