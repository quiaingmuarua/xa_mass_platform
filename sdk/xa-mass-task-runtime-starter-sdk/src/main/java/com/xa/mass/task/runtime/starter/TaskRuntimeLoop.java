package com.xa.mass.task.runtime.starter;

@FunctionalInterface
public interface TaskRuntimeLoop {

    void runOnce(TaskRuntimeLoopContext context);

    default String name() {
        return getClass().getName();
    }

    default long intervalMillis() {
        return 0L;
    }
}
