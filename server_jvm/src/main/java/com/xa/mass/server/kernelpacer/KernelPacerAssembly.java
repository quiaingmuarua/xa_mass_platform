package com.xa.mass.server.kernelpacer;

import com.xa.mass.kernel.pacer.KernelPacerRuntime;
import java.util.Objects;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.SmartLifecycle;

public final class KernelPacerAssembly
        implements SmartLifecycle, DisposableBean {

    public record Snapshot(
            boolean enabled,
            KernelPacerRuntime.Snapshot runtime
    ) {
        public Snapshot {
            Objects.requireNonNull(runtime, "runtime");
        }
    }

    private final KernelPacerProperties properties;
    private final KernelPacerRuntime runtime;

    KernelPacerAssembly(
            KernelPacerProperties properties,
            KernelPacerRuntime runtime
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public void start() {
        if (properties.enabled()) {
            runtime.start();
        }
    }

    @Override
    public void stop() {
        if (properties.enabled()) {
            runtime.stop();
        }
    }

    @Override
    public boolean isRunning() {
        return properties.enabled() && runtime.isRunning();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MIN_VALUE;
    }

    public Snapshot snapshot() {
        return new Snapshot(properties.enabled(), runtime.snapshot());
    }

    @Override
    public void destroy() {
        stop();
    }
}
