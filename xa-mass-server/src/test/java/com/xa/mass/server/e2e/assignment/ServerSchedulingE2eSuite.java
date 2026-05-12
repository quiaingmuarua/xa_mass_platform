package com.xa.mass.server.e2e.assignment;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskApiMultiTaskAssignmentIntegrationTest.class,
        TaskApiMinimumWorkerGateIntegrationTest.class,
        TaskApiDelayedWorkerAvailabilityIntegrationTest.class,
        TaskApiMultiRoundDispatchIntegrationTest.class,
        TaskApiSingleWorkerReuseIntegrationTest.class,
        TaskApiTerminateReuseIntegrationTest.class,
        TaskApiWorkerContextAttributeRoutingIntegrationTest.class,
        TaskApiWorkerWithoutContextIntegrationTest.class,
        PollingWorkerTaskFlowIntegrationTest.class,
        ExternalWorkerPollingApiIntegrationTest.class,
        TransportChannelWiringIntegrationTest.class
})
class ServerSchedulingE2eSuite {
}
