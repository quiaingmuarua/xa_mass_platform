package com.xa.mass.scenarioworkers;

import com.xa.mass.kernel.worker.WorkerPropertyIndexRuntime;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class ScenarioWorkers implements AutoCloseable {

    private final List<ScenarioWorkerBundleLifecycle> bundles;
    private boolean started;
    private boolean closed;

    ScenarioWorkers(
            List<ScenarioWorkerBundleLifecycle> bundles
    ) {
        this.bundles = List.copyOf(bundles);
    }

    public static ScenarioWorkers fromJson(
            String configJson,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime,
            WorkerPropertyIndexRuntime propertyIndexRuntime
    ) {
        Objects.requireNonNull(workerCatalog, "workerCatalog");
        Objects.requireNonNull(workerRuntime, "workerRuntime");
        Objects.requireNonNull(
                propertyIndexRuntime,
                "propertyIndexRuntime"
        );

        List<ScenarioWorkerBundleConfig> configs;
        try {
            configs = ScenarioWorkersJsonParser.parse(configJson);
        } catch (IllegalArgumentException error) {
            throw new ScenarioWorkerAssemblyException(
                    14012,
                    "scenarioWorkers.parseConfig",
                    "Scenario Worker configuration is invalid: "
                            + error.getMessage(),
                    error
            );
        }

        List<ScenarioWorkerBundleLifecycle> bundles =
                new ArrayList<>(configs.size());
        for (ScenarioWorkerBundleConfig config : configs) {
            switch (config.type()) {
                case PHONE_NUMBER -> bundles.add(
                        new PhoneNumberWorkerBundle(
                                config,
                                workerCatalog,
                                workerRuntime,
                                propertyIndexRuntime
                        )
                );
                case STRING_UTILS -> bundles.add(
                        new StringUtilityWorkerBundle(
                                config,
                                workerCatalog,
                                workerRuntime,
                                propertyIndexRuntime
                        )
                );
            }
        }
        return new ScenarioWorkers(bundles);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;

        List<ScenarioWorkerBundleLifecycle> closing =
                new ArrayList<>(bundles);
        Collections.reverse(closing);
        RuntimeException failure = null;
        for (ScenarioWorkerBundleLifecycle bundle : closing) {
            try {
                bundle.close();
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException(
                    "Scenario Workers are closed"
            );
        }
        if (started) {
            return;
        }

        List<ScenarioWorkerBundleLifecycle> startedBundles =
                new ArrayList<>();
        ScenarioWorkerBundleLifecycle starting = null;
        try {
            for (ScenarioWorkerBundleLifecycle bundle : bundles) {
                starting = bundle;
                bundle.start();
                startedBundles.add(bundle);
            }
            started = true;
        } catch (RuntimeException failure) {
            closed = true;
            if (starting != null
                    && !startedBundles.contains(starting)) {
                closeAndSuppress(starting, failure);
            }
            Collections.reverse(startedBundles);
            for (ScenarioWorkerBundleLifecycle bundle : startedBundles) {
                closeAndSuppress(bundle, failure);
            }
            throw failure;
        }
    }

    private static void closeAndSuppress(
            ScenarioWorkerBundleLifecycle bundle,
            RuntimeException failure
    ) {
        try {
            bundle.close();
        } catch (RuntimeException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
