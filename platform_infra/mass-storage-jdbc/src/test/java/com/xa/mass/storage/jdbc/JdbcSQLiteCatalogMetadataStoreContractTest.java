package com.xa.mass.storage.jdbc;

import com.zaxxer.hikari.HikariDataSource;
import com.xa.mass.storage.api.CatalogMetadataStore;
import com.xa.mass.storage.api.CatalogEventRecord;
import com.xa.mass.storage.contract.CatalogMetadataStoreContractTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcSQLiteCatalogMetadataStoreContractTest extends CatalogMetadataStoreContractTest {

    private HikariDataSource dataSource;

    @Override
    protected CatalogMetadataStore createStorage() {
        dataSource = JdbcContractTestFixture.sqliteDataSource();
        return new JdbcCatalogMetadataStore(dataSource, new SQLiteJdbcDialect());
    }

    @Override
    protected void destroyStorage(CatalogMetadataStore storage) {
        dataSource.close();
    }

    @Test
    void sqliteRestoresCatalogMetadataAcrossStoreRestart() {
        storage.upsertCatalog(
                List.of(event("crawler.fetch-page", "crawlerApp")),
                List.of(project("crawlerApp", "crawler.fetch-page"))
        );

        CatalogMetadataStore restarted = new JdbcCatalogMetadataStore(dataSource, new SQLiteJdbcDialect());

        assertThat(restarted.getEvent("crawler.fetch-page")).get()
                .extracting(CatalogEventRecord::projectCodes)
                .isEqualTo(List.of("crawlerApp"));
    }
}
