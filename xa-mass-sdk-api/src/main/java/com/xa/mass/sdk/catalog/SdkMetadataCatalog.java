package com.xa.mass.sdk.catalog;

import com.xa.mass.sdk.event.SdkEventDefinition;

import java.util.List;

/**
 * Read surface for SDK project metadata and runtime-projected event metadata.
 */
public interface SdkMetadataCatalog {

    List<ProjectMetadata> listProjects();

    ProjectMetadata getProject(String projectCode);

    List<SdkEventDefinition> listEvents();

    SdkEventDefinition getEvent(String eventCode);

    List<SdkEventDefinition> getEventsForProject(String projectCode);
}
