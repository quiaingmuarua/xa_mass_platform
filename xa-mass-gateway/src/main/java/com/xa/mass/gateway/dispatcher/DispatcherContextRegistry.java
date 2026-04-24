package com.xa.mass.gateway.dispatcher;

import com.xa.mass.gateway.dispatcher.context.DispatchRuntimeContext;

/**
 * Process-local registry for the current gateway dispatch runtime context.
 */
public class DispatcherContextRegistry {
    private static DispatchRuntimeContext dispatcherContext;

    public static void register(DispatchRuntimeContext ctx) {
        dispatcherContext = ctx;
    }

    public static DispatchRuntimeContext get() {
        return dispatcherContext;
    }

    public static void clear() {
        dispatcherContext = null;
    }
}
