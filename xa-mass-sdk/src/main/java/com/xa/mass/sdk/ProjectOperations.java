package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.ProjectDefinition;
import com.xa.mass.sdk.event.EventDefinition;

import java.util.List;

/**
 * SDK project resource operations.
 */
public interface ProjectOperations {

    /**
     * Register or replace a project resource definition.
     */
    void registerProject(ProjectDefinition projectDefinition);

    /**
     * Register or replace multiple project resource definitions.
     */
    default void registerProjects(List<ProjectDefinition> projectDefinitions) {
        if (projectDefinitions == null) {
            return;
        }
        projectDefinitions.forEach(this::registerProject);
    }

    List<ProjectDefinition> listProjects();

    ProjectDefinition getProject(String projectCode);

    default boolean hasProject(String projectCode) {
        return getProject(projectCode) != null;
    }

    List<EventDefinition> getEventsForProject(String projectCode);

    default boolean projectSupportsEvent(String projectCode, String eventCode) {
        ProjectDefinition projectDefinition = getProject(projectCode);
        return projectDefinition != null
                && eventCode != null
                && projectDefinition.getEventCodes().contains(eventCode);
    }
}
