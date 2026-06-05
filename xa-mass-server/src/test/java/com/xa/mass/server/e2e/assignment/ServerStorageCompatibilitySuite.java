package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.e2e.bootstrap.CatalogMetadataSQLiteRestartIntegrationTest;
import com.xa.mass.server.e2e.bootstrap.CatalogRestoreWorkerCapabilityViewIntegrationTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Support-only storage compatibility coverage.
 *
 * <p>These tests exercise specific backing-storage shells and should not be
 * confused with mainline parity or scheduling proof ownership.
 */
@Suite
@SelectClasses({
        CatalogMetadataSQLiteRestartIntegrationTest.class,
        CatalogRestoreWorkerCapabilityViewIntegrationTest.class,
        H2ExternalWorkerPollingApiIntegrationTest.class,
        PostgresExternalWorkerPollingApiIntegrationTest.class
})
class ServerStorageCompatibilitySuite {
}
