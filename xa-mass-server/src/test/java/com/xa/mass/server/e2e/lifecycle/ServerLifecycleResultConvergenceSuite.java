package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.e2e.results.TaskApiAllMessagesFailedIntegrationTest;
import com.xa.mass.server.e2e.results.TaskApiFailureResultIntegrationTest;
import com.xa.mass.server.e2e.results.TaskApiMixedResultsIntegrationTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskApiIntegrationTest.class,
        TaskApiFailureResultIntegrationTest.class,
        TaskApiLifecycleGuardsIntegrationTest.class,
        TaskApiMixedResultsIntegrationTest.class,
        TaskApiAllMessagesFailedIntegrationTest.class,
        TaskApiBlockedRunningIntegrationTest.class,
        TaskApiPauseCompletionIntegrationTest.class,
        TaskApiResumeAndCompleteIntegrationTest.class,
        TaskApiTerminateRunningIntegrationTest.class
})
class ServerLifecycleResultConvergenceSuite {
}
