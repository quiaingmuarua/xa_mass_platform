package com.xa.mass.engine.work;

public record WorkEnqueueOptions(int maxReadyItemsPerTask) {
    public static final int UNLIMITED = Integer.MAX_VALUE;
    public static final WorkEnqueueOptions DEFAULT = new WorkEnqueueOptions(UNLIMITED);

    public WorkEnqueueOptions {
        maxReadyItemsPerTask = maxReadyItemsPerTask <= 0 ? UNLIMITED : maxReadyItemsPerTask;
    }
}
