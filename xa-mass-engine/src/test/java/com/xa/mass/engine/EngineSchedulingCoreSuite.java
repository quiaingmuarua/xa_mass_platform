package com.xa.mass.engine;

import com.xa.mass.engine.listener.TaskAssignWorkerTest;
import com.xa.mass.engine.listener.TaskResourceReleaseListenerTest;
import com.xa.mass.engine.listener.TaskWorkerAssignListenerTest;
import com.xa.mass.engine.stage.TaskStageEvidenceEventHandlerTest;
import com.xa.mass.engine.stage.TaskStageEvidenceOwnerTest;
import com.xa.mass.engine.command.WorkerCommandDeliveryCoordinatorTest;
import com.xa.mass.engine.command.WorkerCommandLifecycleOwnerTest;
import com.xa.mass.engine.command.WorkerCommandRequestEventHandlerTest;
import com.xa.mass.engine.model.WorkerMatchContextTest;
import com.xa.mass.engine.strategy.RuleBasedTaskWorkerMatchingStrategyTest;
import com.xa.mass.engine.strategy.WorkerSchedulingCandidateEnumeratorTest;
import com.xa.mass.engine.worker.WorkerCandidateIndexTest;
import com.xa.mass.engine.worker.WorkerCapabilityAuthorityTest;
import com.xa.mass.engine.worker.WorkerCapabilityReportEventHandlerTest;
import com.xa.mass.engine.worker.WorkerManagerTest;
import com.xa.mass.engine.worker.WorkerRegistrySnapshotTest;
import com.xa.mass.engine.worker.WorkerStateProjectionOwnerTest;
import com.xa.mass.engine.worker.WorkerStateReportEventHandlerTest;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        EngineSchedulingCoreArchitectureGuardTest.class,
        TaskKernelLifecycleTest.class,
        TaskContractTerminalBehaviorTest.class,
        TaskContractSchedulingBehaviorTest.class,
        TaskSchedulingContentionTest.class,
        TaskWorkerEligibilityTest.class,
        TaskRedispatchCompetitionTest.class,
        TaskSchedulingGateAndTargetingTest.class,
        TaskDelayedAvailabilitySchedulingTest.class,
        TaskRuntimeRecoveryPortTest.class,
        TaskStageEvidenceOwnerTest.class,
        TaskStageEvidenceEventHandlerTest.class,
        WorkerManagerTest.class,
        WorkerCandidateIndexTest.class,
        WorkerCapabilityAuthorityTest.class,
        WorkerCapabilityReportEventHandlerTest.class,
        WorkerCommandDeliveryCoordinatorTest.class,
        WorkerCommandLifecycleOwnerTest.class,
        WorkerCommandRequestEventHandlerTest.class,
        WorkerStateProjectionOwnerTest.class,
        WorkerStateReportEventHandlerTest.class,
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
