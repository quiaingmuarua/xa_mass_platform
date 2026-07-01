package com.xa.mass.task.runtime.starter;

public record TaskRuntimeLoopContext(TaskRuntimePortSet runtime, long nowMillis) {

    public TaskRuntimeLoopContext {
        if (runtime == null) {
            throw new IllegalArgumentException("runtime is required");
        }
        nowMillis = Math.max(0L, nowMillis);
    }
}
