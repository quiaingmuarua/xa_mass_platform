package com.xa.mass.engine;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskStateValidatorBoundaryTest.class
})
class EngineProjectionAuditSuite {
}
