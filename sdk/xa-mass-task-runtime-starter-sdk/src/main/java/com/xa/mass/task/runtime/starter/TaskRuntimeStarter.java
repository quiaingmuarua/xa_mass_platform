package com.xa.mass.task.runtime.starter;

import com.xa.mass.task.runtime.memory.InMemoryTaskRuntime;
import com.xa.mass.task.runtime.redis.RedisTaskRuntime;
import io.lettuce.core.RedisClient;

import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.function.LongSupplier;

public final class TaskRuntimeStarter {

    private TaskRuntimeStarter() {
    }

    public static TaskRuntimeHandle start(TaskRuntimeBootstrapConfig config, List<TaskRuntimeLoop> loops) {
        return start(config, loops, null, System::currentTimeMillis);
    }

    public static TaskRuntimeHandle start(
            TaskRuntimeBootstrapConfig config,
            List<TaskRuntimeLoop> loops,
            ThreadFactory threadFactory,
            LongSupplier clock
    ) {
        var effectiveConfig = config == null ? TaskRuntimeBootstrapConfig.memory() : config;
        var backend = createBackend(effectiveConfig, clock == null ? System::currentTimeMillis : clock);
        var loopHost = new TaskRuntimeLoopHost(
                backend.runtime(),
                loops,
                effectiveConfig.loopIntervalMillis(),
                threadFactory,
                clock);
        var handle = new TaskRuntimeHandle(effectiveConfig.backendKind(), backend.runtime(), loopHost, backend.closeable());
        handle.start();
        return handle;
    }

    private static Backend createBackend(TaskRuntimeBootstrapConfig config, LongSupplier clock) {
        if (config.backendKind() == TaskRuntimeBackendKind.REDIS) {
            var client = RedisClient.create(config.redisUri());
            var runtime = new RedisTaskRuntime(client, config.redisNamespace(), clock);
            return new Backend(new RedisPortSet(runtime), () -> {
                runtime.close();
                client.shutdown();
            });
        }
        var runtime = new InMemoryTaskRuntime(clock);
        return new Backend(new MemoryPortSet(runtime), null);
    }

    private record Backend(TaskRuntimePortSet runtime, AutoCloseable closeable) {
    }
}
