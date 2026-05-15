package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.e2e.support.ServerMainlineE2eArchitectureGuardTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        ServerMainlineE2eArchitectureGuardTest.class,
        TaskApiMultiTaskAssignmentIntegrationTest.class,
        TaskApiAssignmentTraceObservedIntegrationTest.class,
        TaskApiBackgroundWorkerSharingTraceObservedIntegrationTest.class,
        TaskApiMinimumWorkerGateIntegrationTest.class,
        TaskApiDelayedWorkerAvailabilityIntegrationTest.class,
        TaskApiSingleWorkerReuseIntegrationTest.class,
        TaskApiWorkerAttributeRoutingIntegrationTest.class,
        TaskApiWorkerWithoutContextIntegrationTest.class,
        PollingWorkerTaskFlowIntegrationTest.class,
        ExternalWorkerPollingApiIntegrationTest.class,
        TransportChannelWiringIntegrationTest.class
})
class ServerSchedulingE2eSuite {
}
