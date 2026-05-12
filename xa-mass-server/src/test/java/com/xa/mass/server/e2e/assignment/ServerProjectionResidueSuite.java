package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.e2e.results.TaskApiCallbackReplayIntegrationTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskApiMultiRoundDispatchIntegrationTest.class,
        TaskApiTerminateReuseIntegrationTest.class,
        TaskApiCallbackReplayIntegrationTest.class
})
class ServerProjectionResidueSuite {
}
