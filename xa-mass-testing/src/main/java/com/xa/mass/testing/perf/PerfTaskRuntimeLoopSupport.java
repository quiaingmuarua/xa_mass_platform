package com.xa.mass.testing.perf;

import com.xa.mass.engine.EngineRuntimeLoop;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.task.runtime.starter.TaskRuntimeLoop;

import java.util.List;
import java.util.Objects;

final class PerfTaskRuntimeLoopSupport {

    private PerfTaskRuntimeLoopSupport() {
    }

    static void start(EngineConfig engineConfig, EngineRuntimeLoop loop) {
        Objects.requireNonNull(engineConfig, "engineConfig")
                .registerStarterOwnedTaskRuntimeLoops(List.of(toTaskRuntimeLoop(loop)));
    }

    static void stop(EngineConfig engineConfig) {
        if (engineConfig != null) {
            engineConfig.stopStarterOwnedTaskRuntimeLoops();
        }
    }

    private static TaskRuntimeLoop toTaskRuntimeLoop(EngineRuntimeLoop loop) {
        EngineRuntimeLoop engineLoop = Objects.requireNonNull(loop, "loop");
        return new TaskRuntimeLoop() {
            @Override
            public void runOnce(com.xa.mass.task.runtime.starter.TaskRuntimeLoopContext context) {
                engineLoop.runOnce();
            }

            @Override
            public String name() {
                return engineLoop.name();
            }

            @Override
            public long intervalMillis() {
                return engineLoop.intervalMillis();
            }
        };
    }
}
