package com.xa.mass.scenarioworkers;

import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime;
import java.util.Objects;

public final class ScenarioWorkerBundles {

    private ScenarioWorkerBundles() {
    }

    public static ScenarioWorkerBundle phoneNumber(
            ScenarioWorkerBundleConfig config,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime
    ) {
        return new ScenarioWorkerBundle(
                new PhoneNumberWorkerBundle(
                        Objects.requireNonNull(config, "config"),
                        Objects.requireNonNull(
                                workerCatalog,
                                "workerCatalog"
                        ),
                        Objects.requireNonNull(
                                workerRuntime,
                                "workerRuntime"
                        )
                )
        );
    }

    public static ScenarioWorkerBundle stringUtils(
            ScenarioWorkerBundleConfig config,
            WorkerResourceCatalog workerCatalog,
            WorkerRuntime workerRuntime
    ) {
        return new ScenarioWorkerBundle(
                new StringUtilityWorkerBundle(
                        Objects.requireNonNull(config, "config"),
                        Objects.requireNonNull(
                                workerCatalog,
                                "workerCatalog"
                        ),
                        Objects.requireNonNull(
                                workerRuntime,
                                "workerRuntime"
                        )
                )
        );
    }
}
