package com.xa.mass.storage.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Durable project/event catalog metadata store.
 */
public interface CatalogMetadataStore {

    void upsertEvent(CatalogEventRecord event);

    void upsertProject(CatalogProjectRecord project);

    void validateUpsertCatalog(Collection<CatalogEventRecord> events,
                               Collection<CatalogProjectRecord> projects);

    void upsertCatalog(Collection<CatalogEventRecord> events,
                       Collection<CatalogProjectRecord> projects);

    Optional<CatalogEventRecord> getEvent(String eventCode);

    Optional<CatalogProjectRecord> getProject(String projectCode);

    List<CatalogEventRecord> listEvents();

    List<CatalogProjectRecord> listProjects();

    void clear();
}
