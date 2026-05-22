package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.e2e.support.ServerMainlineE2eArchitectureGuardTest;
import com.xa.mass.server.e2e.support.ServerProofOwnershipGuardTest;
import com.xa.mass.server.e2e.results.TaskApiAllMessagesFailedTraceObservedIntegrationTest;
import com.xa.mass.server.e2e.results.TaskApiCallbackReplayTraceObservedIntegrationTest;
import com.xa.mass.server.e2e.results.TaskApiFailureResultIntegrationTest;
import com.xa.mass.server.e2e.results.TaskApiMixedResultsTraceObservedIntegrationTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        ServerMainlineE2eArchitectureGuardTest.class,
        ServerProofOwnershipGuardTest.class,
        TaskApiCallbackReplayTraceObservedIntegrationTest.class,
        TaskApiFailureResultIntegrationTest.class,
        TaskApiLifecycleGuardsIntegrationTest.class,
        TaskApiMixedResultsTraceObservedIntegrationTest.class,
        TaskApiAllMessagesFailedTraceObservedIntegrationTest.class,
        TaskApiBlockedRunningIntegrationTest.class,
        TaskApiPauseCompletionIntegrationTest.class,
        TaskApiResumeAndCompleteIntegrationTest.class,
        TaskApiTerminateRunningIntegrationTest.class
})
class ServerLifecycleResultConvergenceSuite {
}
