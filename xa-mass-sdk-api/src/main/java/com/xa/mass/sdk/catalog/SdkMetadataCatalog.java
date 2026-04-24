package com.xa.mass.sdk.catalog;

import com.xa.mass.sdk.event.EventDefinition;

import java.util.List;

/**
 * Read surface for SDK project metadata and runtime-projected event metadata.
 */
public interface SdkMetadataCatalog {

    List<ProjectMetadata> listProjects();

    ProjectMetadata getProject(String projectCode);

    List<EventDefinition> listEvents();

    EventDefinition getEvent(String eventCode);

    List<EventDefinition> getEventsForProject(String projectCode);
}
