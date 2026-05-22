package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.base.runtime.dispatch.TaskDispatchContext;

import java.util.List;

/**
 * Runtime-owned hook for compensating dispatch batches that failed after the
 * engine already produced concrete dispatch bindings.
 */
@FunctionalInterface
public interface TransportDispatchFailureHandler {

    boolean compensate(TaskDispatchContext task, List<TaskDispatchBinding> dispatchBindings, String detail);
}
