package com.xa.mass.engine;

import com.xa.mass.engine.listener.TaskAssignWorkerTest;
import com.xa.mass.engine.listener.TaskResourceReleaseListenerTest;
import com.xa.mass.engine.listener.TaskWorkerAssignListenerTest;
import com.xa.mass.engine.control.WorkerControlServiceTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        TaskKernelLifecycleTest.class,
        TaskIdleClosePolicyBehaviorTest.class,
        TaskPolicySchedulingOutcomeTest.class,
        TaskSchedulingContentionTest.class,
        TaskWorkerEligibilityTest.class,
        WorkerStateReportSchedulingIntegrationTest.class,
        TaskSchedulingBindingEntryBypassTest.class,
        TaskRedispatchCompetitionTest.class,
        TaskSchedulingGateAndTargetingTest.class,
        TaskDelayedAvailabilitySchedulingTest.class,
        TaskRuntimeRecoveryPortTest.class,
        WorkerControlServiceTest.class,
        TaskResourceReleaseListenerTest.class,
        TaskAssignWorkerTest.class,
        TaskWorkerAssignListenerTest.class
})
class EngineSchedulingCoreSuite {
}
