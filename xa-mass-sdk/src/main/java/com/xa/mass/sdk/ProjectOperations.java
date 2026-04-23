package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.ProjectMetadata;
import com.xa.mass.sdk.event.SdkEventDefinition;

import java.util.List;

/**
 * SDK project resource operations.
 */
public interface ProjectOperations {

    /**
     * Register or replace project metadata.
     */
    void registerProject(ProjectMetadata projectMetadata);

    /**
     * Register or replace multiple project metadata entries.
     */
    default void registerProjects(List<ProjectMetadata> projectMetadataList) {
        if (projectMetadataList == null) {
            return;
        }
        projectMetadataList.forEach(this::registerProject);
    }

    List<ProjectMetadata> listProjects();

    ProjectMetadata getProject(String projectCode);

    default boolean hasProject(String projectCode) {
        return getProject(projectCode) != null;
    }

    List<SdkEventDefinition> getEventsForProject(String projectCode);

    default boolean projectSupportsEvent(String projectCode, String eventCode) {
        ProjectMetadata projectMetadata = getProject(projectCode);
        return projectMetadata != null
                && eventCode != null
                && projectMetadata.getEventCodes().contains(eventCode);
    }
}
