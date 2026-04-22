package com.xa.mass.sdk.catalog;

import java.util.List;

/**
 * Read surface for project and task-event metadata.
 */
public interface ProjectEventCatalog {

    List<ProjectMetadata> listProjects();

    ProjectMetadata getProject(String projectCode);

    List<EventMetadata> listEvents();

    EventMetadata getEvent(String eventCode);

    List<EventMetadata> getEventsForProject(String projectCode);
}
