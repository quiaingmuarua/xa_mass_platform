package com.xa.mass.storage.contract;

import com.xa.mass.storage.api.CatalogEventRecord;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.api.CatalogProjectRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Behavioural contract for durable project/event catalog metadata stores.
 */
public abstract class CatalogMetadataStoreContractTest {

    protected CatalogMetadataStore storage;

    protected abstract CatalogMetadataStore createStorage();

    protected void destroyStorage(CatalogMetadataStore storage) {
    }

    @BeforeEach
    void setUp() {
        storage = createStorage();
    }

    @AfterEach
    void tearDown() {
        destroyStorage(storage);
    }

    @Test
    void emptyStoreReturnsNoCatalogMetadata() {
        assertThat(storage.listEvents()).isEmpty();
        assertThat(storage.listProjects()).isEmpty();
        assertThat(storage.getEvent("missing")).isEmpty();
        assertThat(storage.getProject("missing")).isEmpty();
    }

    @Test
    void upsertCatalogPersistsEventsProjectsAndBindings() {
        storage.upsertCatalog(
                List.of(event("crawler.fetch-page", "crawlerApp")),
                List.of(project("crawlerApp", "crawler.fetch-page"))
        );

        assertThat(storage.getEvent("crawler.fetch-page")).get()
                .extracting(CatalogEventRecord::projectCodes)
                .isEqualTo(List.of("crawlerApp"));
        assertThat(storage.getProject("crawlerApp")).get()
                .extracting(CatalogProjectRecord::eventCodes)
                .isEqualTo(List.of("crawler.fetch-page"));
    }

    @Test
    void upsertCatalogRejectsProjectBindingToUnknownEventBeforeWritingProject() {
        assertThatThrownBy(() -> storage.upsertCatalog(
                List.of(),
                List.of(project("crawlerApp", "crawler.fetch-page"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown event code");

        assertThat(storage.getProject("crawlerApp")).isEmpty();
    }

    @Test
    void replaceByCodeUpdatesBindingsWithoutDuplicatingEventDefinition() {
        storage.upsertCatalog(
                List.of(event("crawler.fetch-page", "crawlerApp")),
                List.of(project("crawlerApp", "crawler.fetch-page"))
        );

        storage.upsertCatalog(
                List.of(event("crawler.fetch-page", "opsApp")),
                List.of(project("opsApp", "crawler.fetch-page"))
        );

        assertThat(storage.listEvents())
                .extracting(CatalogEventRecord::code)
                .containsExactly("crawler.fetch-page");
        assertThat(storage.getEvent("crawler.fetch-page")).get()
                .extracting(CatalogEventRecord::projectCodes)
                .isEqualTo(List.of("opsApp"));
        assertThat(storage.getProject("crawlerApp")).get()
                .extracting(CatalogProjectRecord::eventCodes)
                .isEqualTo(List.of());
        assertThat(storage.getProject("opsApp")).get()
                .extracting(CatalogProjectRecord::eventCodes)
                .isEqualTo(List.of("crawler.fetch-page"));
    }

    @Test
    void clearRemovesCatalogMetadata() {
        storage.upsertCatalog(
                List.of(event("crawler.fetch-page", "crawlerApp")),
                List.of(project("crawlerApp", "crawler.fetch-page"))
        );

        storage.clear();

        assertThat(storage.listEvents()).isEmpty();
        assertThat(storage.listProjects()).isEmpty();
    }

    protected CatalogEventRecord event(String code, String... projectCodes) {
        return new CatalogEventRecord(
                code,
                "Event " + code,
                "description",
                List.of("JSON"),
                List.of("SINGLE_RUN"),
                true,
                null,
                List.of(projectCodes),
                "STANDARD",
                "FINAL_RESULT",
                "NONE",
                "FINAL_RESULT",
                "WORKER"
        );
    }

    protected CatalogProjectRecord project(String code, String... eventCodes) {
        return new CatalogProjectRecord(
                "default",
                code,
                "Project " + code,
                "description",
                true,
                null,
                List.of(eventCodes)
        );
    }
}
