package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.e2e.support.ServerProofOwnershipGuardTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.ExcludeTags;
import org.junit.platform.suite.api.Suite;

@Suite
@ExcludeTags("projection-residue")
@SelectClasses({
        ServerProofOwnershipGuardTest.class,
        ExternalWorkerPublicContractTraceObservedIntegrationTest.class,
        ExternalWorkerPollingApiIntegrationTest.class,
        NodePollingWorkerBlackBoxIntegrationTest.class,
        JavaPollingWorkerBlackBoxIntegrationTest.class,
        NodeWebSocketWorkerBlackBoxIntegrationTest.class,
        JavaWebSocketWorkerBlackBoxIntegrationTest.class,
        NodeSocketWorkerBlackBoxIntegrationTest.class,
        JavaSocketWorkerBlackBoxIntegrationTest.class
})
class ExternalWorkerParitySuite {
}
