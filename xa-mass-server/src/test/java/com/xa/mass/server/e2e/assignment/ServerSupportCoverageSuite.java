package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.e2e.support.CatalogApiIntegrationTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Support-only smoke and shell coverage.
 *
 * <p>These tests remain useful for local confidence and broad regression
 * sweeps, but they are intentionally outside registry-backed mainline proof
 * ownership. Keep new authoritative or representative proof out of this suite.
 */
@Suite
@SelectClasses({
        CatalogApiIntegrationTest.class,
        DevSampleWorkerLauncherIntegrationTest.class,
        ExternalWorkerRealtimeRegistrationIntegrationTest.class,
        TaskApiDelayedWorkerAvailabilityIntegrationTest.class,
        TaskApiMinimumWorkerGateIntegrationTest.class,
        TaskApiSingleWorkerReuseIntegrationTest.class,
        TaskApiTargetedWorkerDebugIntegrationTest.class,
        TaskApiWorkerAttributeRoutingIntegrationTest.class,
})
class ServerSupportCoverageSuite {
}
