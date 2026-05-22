package com.xa.mass.server.e2e.audit;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskApiStateValidationIntegrationTest.class
})
class ServerProjectionAuditSuite {
}
