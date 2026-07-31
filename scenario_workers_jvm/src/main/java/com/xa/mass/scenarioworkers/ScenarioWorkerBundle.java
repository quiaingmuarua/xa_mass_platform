package com.xa.mass.scenarioworkers;

import java.util.Objects;

public final class ScenarioWorkerBundle implements AutoCloseable {

    private final ScenarioWorkerBundleLifecycle lifecycle;

    ScenarioWorkerBundle(ScenarioWorkerBundleLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(
                lifecycle,
                "lifecycle"
        );
    }

    public String bundleId() {
        return lifecycle.bundleId();
    }

    public void start() {
        lifecycle.start();
    }

    @Override
    public void close() {
        lifecycle.close();
    }
}
