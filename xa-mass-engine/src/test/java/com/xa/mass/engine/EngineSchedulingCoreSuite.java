package com.xa.mass.engine;

import com.xa.mass.engine.assignment.DefaultAssignmentAllocationPolicyTest;
import com.xa.mass.engine.assignment.DefaultWorkerBudgetPolicyTest;
import com.xa.mass.engine.listener.TaskAssignWorkerTest;
import com.xa.mass.engine.listener.TaskResourceReleaseListenerTest;
import com.xa.mass.engine.listener.TaskWorkerAssignListenerTest;
import com.xa.mass.engine.model.WorkerMatchContextTest;
import com.xa.mass.engine.strategy.RuleBasedTaskWorkerMatchingStrategyTest;
import com.xa.mass.engine.strategy.WorkerSchedulingCandidateEnumeratorTest;
import com.xa.mass.engine.control.WorkerControlServiceTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        EngineSchedulingCoreArchitectureGuardTest.class,
        EngineProofOwnershipGuardTest.class,
        TaskKernelLifecycleTest.class,
        TaskContractTerminalBehaviorTest.class,
        TaskContractSchedulingBehaviorTest.class,
        TaskSchedulingContentionTest.class,
        TaskWorkerEligibilityTest.class,
        TaskRedispatchCompetitionTest.class,
        TaskSchedulingGateAndTargetingTest.class,
        TaskDelayedAvailabilitySchedulingTest.class,
        TaskRuntimeRecoveryPortTest.class,
        WorkerControlServiceTest.class,
        TaskResourceReleaseListenerTest.class,
        TaskAssignWorkerTest.class,
        TaskWorkerAssignListenerTest.class,
        DefaultAssignmentAllocationPolicyTest.class,
        DefaultWorkerBudgetPolicyTest.class,
        WorkerSchedulingCandidateEnumeratorTest.class,
        RuleBasedTaskWorkerMatchingStrategyTest.class,
        WorkerMatchContextTest.class
})
class EngineSchedulingCoreSuite {
}
