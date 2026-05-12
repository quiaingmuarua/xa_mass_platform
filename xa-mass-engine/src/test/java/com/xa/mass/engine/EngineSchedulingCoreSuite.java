package com.xa.mass.engine;

import com.xa.mass.engine.listener.TaskAssignWorkerTest;
import com.xa.mass.engine.listener.TaskResourceReleaseListenerTest;
import com.xa.mass.engine.listener.TaskWorkerAssignListenerTest;
import com.xa.mass.engine.model.WorkerMatchContextTest;
import com.xa.mass.engine.strategy.RuleBasedTaskWorkerMatchingStrategyTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskConcurrencyAcceptanceTest.class,
        TaskManagerLifecycleTest.class,
        TaskResourceReleaseListenerTest.class,
        TaskAssignWorkerTest.class,
        TaskWorkerAssignListenerTest.class,
        RuleBasedTaskWorkerMatchingStrategyTest.class,
        WorkerMatchContextTest.class
})
class EngineSchedulingCoreSuite {
}
