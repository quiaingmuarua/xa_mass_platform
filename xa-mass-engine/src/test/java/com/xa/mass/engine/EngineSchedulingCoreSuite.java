package com.xa.mass.engine;

import com.xa.mass.engine.listener.TaskAssignWorkerTest;
import com.xa.mass.engine.listener.TaskResourceReleaseListenerTest;
import com.xa.mass.engine.listener.TaskWorkerAssignListenerTest;
import com.xa.mass.engine.model.WorkerMatchContextTest;
import com.xa.mass.engine.strategy.RuleBasedTaskWorkerMatchingStrategyTest;
import com.xa.mass.engine.strategy.WorkerSchedulingCandidateEnumeratorTest;
import com.xa.mass.engine.worker.WorkerCandidateIndexTest;
import com.xa.mass.engine.worker.WorkerCapabilityAuthorityTest;
import com.xa.mass.engine.worker.WorkerControlServiceTest;
import com.xa.mass.engine.worker.WorkerManagerTest;
import com.xa.mass.engine.worker.WorkerRegistrySnapshotTest;
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
        WorkerManagerTest.class,
        WorkerCandidateIndexTest.class,
        WorkerCapabilityAuthorityTest.class,
        WorkerControlServiceTest.class,
        WorkerRegistrySnapshotTest.class,
        TaskResourceReleaseListenerTest.class,
        TaskAssignWorkerTest.class,
        TaskWorkerAssignListenerTest.class,
        WorkerSchedulingCandidateEnumeratorTest.class,
        RuleBasedTaskWorkerMatchingStrategyTest.class,
        WorkerMatchContextTest.class
})
class EngineSchedulingCoreSuite {
}
