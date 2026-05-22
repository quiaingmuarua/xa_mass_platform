package com.xa.mass.server.e2e.assignment;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskApiMultiRoundDispatchIntegrationTest.class,
        TaskApiTerminateReuseIntegrationTest.class
})
class ServerProjectionResidueSuite {
}
