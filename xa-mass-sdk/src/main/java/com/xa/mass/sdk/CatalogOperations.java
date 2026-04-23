package com.xa.mass.sdk;

import com.xa.mass.sdk.catalog.EventMetadata;
import com.xa.mass.sdk.catalog.ProjectEventCatalog;
import com.xa.mass.sdk.catalog.ProjectMetadata;

import java.util.List;

/**
 * SDK catalog and control-plane resource operations.
 *
 * <p>The catalog is the SDK-facing project/event directory used by clients,
 * examples, and platform shells to discover supported capabilities.
 */
public interface CatalogOperations {

    void registerProject(ProjectMetadata projectMetadata);

    void registerEvent(EventMetadata eventMetadata);

    List<ProjectMetadata> listProjects();

    ProjectMetadata getProject(String projectCode);

    List<EventMetadata> listEvents();

    EventMetadata getEvent(String eventCode);

    List<EventMetadata> getEventsForProject(String projectCode);

    ProjectEventCatalog projectEventCatalog();
}
