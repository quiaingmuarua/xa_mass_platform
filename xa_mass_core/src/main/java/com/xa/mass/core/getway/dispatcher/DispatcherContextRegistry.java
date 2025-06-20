package com.xa.mass.core.getway.dispatcher;

public class DispatcherContextRegistry {
    private static DispatcherContext dispatcherContext;

    public static void register(DispatcherContext ctx) {
        dispatcherContext = ctx;
    }

    public static DispatcherContext get() {
        return dispatcherContext;
    }
} 