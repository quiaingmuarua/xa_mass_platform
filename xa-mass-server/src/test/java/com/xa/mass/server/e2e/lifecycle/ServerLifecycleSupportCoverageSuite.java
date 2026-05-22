package com.xa.mass.server.e2e.lifecycle;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

/**
 * Support-only lifecycle smoke coverage.
 *
 * <p>These tests remain useful for broad shell confidence, but they are outside
 * registry-backed lifecycle/result proof ownership.
 */
@Suite
@SelectClasses({
        SdkTaskApiIntegrationTest.class,
        TaskApiIntegrationTest.class
})
class ServerLifecycleSupportCoverageSuite {
}
