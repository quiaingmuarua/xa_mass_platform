package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.e2e.results.TaskApiAllMessagesFailedIntegrationTest;
import com.xa.mass.server.e2e.results.TaskApiMixedResultsIntegrationTest;
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
        TaskApiIntegrationTest.class,
        TaskApiAllMessagesFailedIntegrationTest.class,
        TaskApiMixedResultsIntegrationTest.class
})
class ServerLifecycleSupportCoverageSuite {
}
