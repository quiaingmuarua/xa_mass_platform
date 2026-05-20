package com.xa.mass.server.e2e.assignment;

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
        DevSampleWorkerLauncherIntegrationTest.class,
        ExternalWorkerRealtimeRegistrationIntegrationTest.class,
        TaskApiTargetedWorkerDebugIntegrationTest.class,
})
class ServerSupportCoverageSuite {
}
