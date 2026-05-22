package com.xa.mass.engine;

import com.xa.mass.engine.listener.SimpleTaskDispatchBinderTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskManagerLifecycleTest.class,
        SimpleTaskDispatchBinderTest.class
})
class EngineProjectionResidueSuite {
}
