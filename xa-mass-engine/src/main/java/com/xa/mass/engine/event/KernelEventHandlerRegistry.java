package com.xa.mass.engine.event;

import com.xa.mass.base.event.TargetScope;
import com.xa.mass.command.event.CoreEventDescriptor;
import com.xa.mass.command.event.MassEventHandler;
import com.xa.mass.command.event.MassEventRuntime;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Engine-local registration boundary for kernel-targeted event handlers.
 *
 * <p>This class owns routing registration only. It does not own command,
 * worker-state, worker-capability, task-result, or presence lifecycle truth.</p>
 */
public final class KernelEventHandlerRegistry {

    private static final Set<TargetScope> KERNEL_TARGETS = EnumSet.of(
            TargetScope.TASK_ENGINE,
            TargetScope.WORKER_MANAGER
    );

    private final MassEventRuntime eventRuntime;

    public KernelEventHandlerRegistry(MassEventRuntime eventRuntime) {
        this.eventRuntime = Objects.requireNonNull(eventRuntime, "eventRuntime");
    }

    public void register(CoreEventDescriptor descriptor, MassEventHandler handler) {
        CoreEventDescriptor normalized = validateKernelTarget(descriptor);
        eventRuntime.register(normalized, Objects.requireNonNull(handler, "handler"));
    }

    public void registerOrReplace(CoreEventDescriptor descriptor, MassEventHandler handler) {
        CoreEventDescriptor normalized = validateKernelTarget(descriptor);
        eventRuntime.registerOrReplace(normalized, Objects.requireNonNull(handler, "handler"));
    }

    public void registerWorkerManagerEvent(String event, MassEventHandler handler) {
        register(CoreEventDescriptor.builder()
                .event(event)
                .targetScope(TargetScope.WORKER_MANAGER)
                .build(), handler);
    }

    public void registerOrReplaceWorkerManagerEvent(String event, MassEventHandler handler) {
        registerOrReplace(CoreEventDescriptor.builder()
                .event(event)
                .targetScope(TargetScope.WORKER_MANAGER)
                .build(), handler);
    }

    private static CoreEventDescriptor validateKernelTarget(CoreEventDescriptor descriptor) {
        CoreEventDescriptor normalized = Objects.requireNonNull(descriptor, "descriptor");
        TargetScope targetScope = normalized.getTargetScope();
        if (!KERNEL_TARGETS.contains(targetScope)) {
            throw new IllegalArgumentException("kernel event handler target must be one of "
                    + KERNEL_TARGETS + ": " + normalized.getEvent());
        }
        return normalized;
    }
}
