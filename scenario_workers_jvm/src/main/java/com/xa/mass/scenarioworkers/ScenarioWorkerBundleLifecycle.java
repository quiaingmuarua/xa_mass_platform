package com.xa.mass.scenarioworkers;

interface ScenarioWorkerBundleLifecycle extends AutoCloseable {

    String bundleId();

    void start();

    @Override
    void close();
}
