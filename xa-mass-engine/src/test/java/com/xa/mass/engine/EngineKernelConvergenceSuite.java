package com.xa.mass.engine;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({
        EngineKernelConvergenceArchitectureGuardTest.class,
        TaskKernelLifecycleTest.class,
        TaskContractTerminalBehaviorTest.class,
        TaskResultRuntimeConvergenceTest.class,
        TaskRuntimeRecoveryPortTest.class
})
class EngineKernelConvergenceSuite {
}
