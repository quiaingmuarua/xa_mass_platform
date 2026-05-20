package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.e2e.support.ServerMainlineE2eArchitectureGuardTest;
import com.xa.mass.server.e2e.support.ServerProofOwnershipGuardTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        ServerMainlineE2eArchitectureGuardTest.class,
        ServerProofOwnershipGuardTest.class,
        TaskApiMultiTaskAssignmentIntegrationTest.class,
        TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest.class,
        TaskApiWorkerAttributeRoutingTraceObservedIntegrationTest.class,
        TaskApiCrossTaskWorkerFairnessTraceObservedIntegrationTest.class,
        TaskApiMinimumWorkerGateTraceObservedIntegrationTest.class,
        TaskApiDelayedWorkerAvailabilityTraceObservedIntegrationTest.class,
        TaskApiMinimumWorkerGateIntegrationTest.class,
        TaskApiDelayedWorkerAvailabilityIntegrationTest.class,
        TaskApiRetryRedispatchTraceObservedIntegrationTest.class,
        TaskApiSingleWorkerReuseTraceObservedIntegrationTest.class,
        TaskApiSingleWorkerReuseIntegrationTest.class,
        TaskApiWorkerAttributeRoutingIntegrationTest.class,
        TaskApiWorkerWithoutContextIntegrationTest.class,
})
class ServerSchedulingE2eSuite {
}
